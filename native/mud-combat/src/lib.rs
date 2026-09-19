//! Numeric combat batch. Java owns entities, ordering, cooldowns, rewards and slow expiry.
//! C ABI v1: enemy rows [x,z,health,boss], defender rows
//! [x,z,range,radius,damage,role,ability,limit,previous_chain,previous_target_index].
//! Output rows [primary_index, damage_for_enemy_0, ...]; -1 means no hit.
use std::slice;

#[unsafe(no_mangle)]
pub extern "C" fn mud_combat_abi() -> i32 {
    1
}

fn distance(x: f64, z: f64, a: f64, b: f64) -> f64 {
    let dx = x - a;
    let dz = z - b;
    dx * dx + dz * dz
}

fn in_cone(x: f64, z: f64, px: f64, pz: f64, ex: f64, ez: f64) -> bool {
    let ax = px - x;
    let az = pz - z;
    let bx = ex - x;
    let bz = ez - z;
    let lengths = ((ax * ax + az * az) * (bx * bx + bz * bz)).sqrt();
    lengths == 0.0 || (ax * bx + az * bz) / lengths >= 0.5
}

fn batch(enemies: &mut [f64], defenders: &[f64], output: &mut [f64]) {
    let n = enemies.len() / 4;
    output.fill(-1.0);
    for (d, row) in defenders.chunks_exact(10).enumerate() {
        let (x, z, range, radius, base) = (row[0], row[1], row[2], row[3], row[4]);
        let (role, level, limit) = (row[5] as i32, row[6] as i32, row[7] as usize);
        let primary = (0..n).find(|&e| {
            enemies[e * 4 + 2] > 0.0
                && distance(x, z, enemies[e * 4], enemies[e * 4 + 1]) <= range * range
        });
        let Some(primary) = primary else { continue };
        let offset = d * (n + 1);
        output[offset] = primary as f64;
        let chain = if row[9] == primary as f64 {
            (row[8] as i32 + 1).min(5)
        } else {
            1
        };
        let (px, pz) = (enemies[primary * 4], enemies[primary * 4 + 1]);
        let mut count = 1;
        // Select every target against pre-attack health before applying this defender's damage.
        for e in 0..n {
            if enemies[e * 4 + 2] <= 0.0 {
                continue;
            }
            let (ex, ez) = (enemies[e * 4], enemies[e * 4 + 1]);
            let selected = e == primary
                || match role {
                    0 | 2 => false,
                    1 => distance(x, z, ex, ez) <= range * range && in_cone(x, z, px, pz, ex, ez),
                    3 | 4 => distance(px, pz, ex, ez) <= radius * radius,
                    5 => count < limit && distance(x, z, ex, ez) <= range * range,
                    _ => false,
                };
            if !selected {
                continue;
            }
            if e != primary {
                count += 1;
            }
            let multiplier = if level > 0 {
                match role {
                    0 => 1.0 + 0.06 * f64::from(level) * f64::from(chain - 1),
                    2 if enemies[e * 4 + 3] != 0.0 => 1.0 + 0.5 * f64::from(level),
                    3 if e == primary => 1.0 + 0.3 * f64::from(level),
                    _ => 1.0,
                }
            } else {
                1.0
            };
            output[offset + 1 + e] = base * multiplier;
        }
        for e in 0..n {
            let damage = output[offset + 1 + e];
            if damage >= 0.0 {
                enemies[e * 4 + 2] = (enemies[e * 4 + 2] - damage).max(0.0);
            }
        }
    }
}

/// Executes sequential attacks over priority-sorted enemies; no Java/Minecraft pointers.
///
/// # Safety
/// Non-overlapping, 8-byte-aligned buffers must cover n*4, m*10 and m*(n+1) doubles.
/// Enemy and output buffers must be writable for the duration of this synchronous call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mud_combat_batch(
    enemies: *mut f64,
    n: i32,
    defenders: *const f64,
    m: i32,
    output: *mut f64,
) -> i32 {
    if !(1..=4096).contains(&n) || !(1..=225).contains(&m) {
        return -1;
    }
    if enemies.is_null()
        || defenders.is_null()
        || output.is_null()
        || !enemies.is_aligned()
        || !defenders.is_aligned()
        || !output.is_aligned()
    {
        return -2;
    }
    let (n, m) = (n as usize, m as usize);
    // SAFETY: lengths/alignment checked above; caller owns validity and non-aliasing.
    let enemies = unsafe { slice::from_raw_parts_mut(enemies, n * 4) };
    let defenders = unsafe { slice::from_raw_parts(defenders, m * 10) };
    let output = unsafe { slice::from_raw_parts_mut(output, m * (n + 1)) };
    if enemies
        .iter()
        .chain(defenders.iter())
        .any(|v| !v.is_finite())
    {
        return -3;
    }
    if enemies.chunks_exact(4).any(|e| e[2] < 0.0)
        || defenders.chunks_exact(10).any(|d| {
            d[2] < 0.0
                || d[3] < 0.0
                || d[4] < 0.0
                || d[5] < 0.0
                || d[5] > 5.0
                || d[5].fract() != 0.0
                || d[6] < 0.0
                || d[6] > 4.0
                || d[7] < 1.0
        })
    {
        return -4;
    }
    batch(enemies, defenders, output);
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sequential_kills_retarget_and_multi_never_repeats() {
        let mut enemies = [0., 0., 5., 0., 1., 0., 100., 0., 2., 0., 100., 0.];
        let defenders = [
            0., 0., 10., 0., 10., 0., 0., 1., 0., -1., 0., 0., 10., 0., 7., 5., 0., 5., 0., -1.,
        ];
        let mut out = [-1.; 8];
        batch(&mut enemies, &defenders, &mut out);
        assert_eq!(out, [0., 10., -1., -1., 1., -1., 7., 7.]);
        assert_eq!(enemies[2], 0.);
        assert_eq!(enemies[6], 93.);
    }

    #[test]
    fn cone_range_and_center_bonus_boundaries() {
        assert!(in_cone(0., 0., 1., 0., 1., 0.));
        assert!(!in_cone(0., 0., 1., 0., -1., 0.));
        let mut enemies = [3., 0., 100., 0., 4., 0., 100., 0., 4.001, 0., 100., 0.];
        let defenders = [0., 0., 3., 1., 10., 3., 2., 1., 0., -1.];
        let mut out = [-1.; 4];
        batch(&mut enemies, &defenders, &mut out);
        assert_eq!(out, [0., 16., 10., -1.]);
    }

    #[test]
    fn rejects_invalid_abi_arguments_without_accessing_memory() {
        assert_eq!(
            unsafe {
                mud_combat_batch(
                    std::ptr::null_mut(),
                    1,
                    std::ptr::null(),
                    1,
                    std::ptr::null_mut(),
                )
            },
            -2
        );
        assert_eq!(
            unsafe {
                mud_combat_batch(
                    std::ptr::null_mut(),
                    -1,
                    std::ptr::null(),
                    1,
                    std::ptr::null_mut(),
                )
            },
            -1
        );
        assert_eq!(mud_combat_abi(), 1);
    }
}
