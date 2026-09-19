# Rust 전투 계산 모듈

Java 25 FFM으로 세션의 공격 가능 포탑을 한 번에 처리하는 선택 모듈입니다. 코어의 적 우선순위 정렬 이후 거리·부채꼴·광역·다중 판정과 순차 피해 계산을 Rust로 넘깁니다. Java는 결과를 검사한 후 체력·감속·연타·쿨다운·보상을 갱신합니다. Minecraft 객체 주소는 이 모듈로 전달하지 않습니다. 실제 NMS 객체를 조작하는 별도 JNI 모듈은 서버 저장소 native/mud-entities에 있습니다.

~~~powershell
cargo test --manifest-path native/mud-combat/Cargo.toml
cargo build --release --locked --manifest-path native/mud-combat/Cargo.toml
mvn -B -ntp "-Dmud.native.library=E:/projects/moma/native/mud-combat/target/release/mud_combat.dll" verify
java --enable-native-access=ALL-UNNAMED -Dmud.native.library=<절대경로/mud_combat.dll> -jar <서버.jar> --nogui
~~~

Linux 파일명은 libmud_combat.so입니다. 기본은 Java이며 속성에 로컬 라이브러리 경로를 지정해야 켜집니다. ABI 버전 불일치/로드 실패는 Java로 돌아가며, 계산 오류 시 결과를 적용하기 전에 검증해 중복 피해를 막습니다. JVM 프로세스에서 한 번 선택하므로 전환은 재시작합니다. 출력이 적용된 뒤 sink가 게임 상태를 바꾸는 외부 용도는 지원하지 않습니다. 현재 sink는 이펙트 ID 수집과 피해 통계만 수행합니다.

검증은 단일 숫자 정밀도 오차를 허용하지 않고 Java/Rust의 명중 순서·체력·감속·연타·쿨다운을 비교합니다. 50개 시드 전체 결과도 동일함을 확인했습니다. 성능은 docs/performance의 실제 서버 비교를 기준으로 판단하며 Rust라는 이유만으로 빠르다고 가정하지 않습니다.
