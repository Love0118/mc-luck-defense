"""Local Playwright QA for the self-contained replay. No web server needed."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "target" / "qa-deps"))
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto((ROOT / "docs/simulation/economy-0.9.0/example-clear/replay.html").as_uri())
    assert "6×6 · 36칸" in page.locator("#placement").inner_text()
    assert page.evaluate("gridSize === 6 && routeSide === 21")
    assert page.evaluate("frames.every(f => f.units.every(u => u.column >= 0 && u.column < 6 && u.row >= 0 && u.row < 6))")
    assert page.evaluate("summary.startingGold === 30 && summary.regularRewardsByDecade[0] === 0.1")
    page.locator("#time").evaluate("el => { el.value = el.max; el.dispatchEvent(new Event('input')); }")
    assert "R100/100" in page.locator("#stats").inner_text()
    assert page.evaluate("frames.at(-1).enemies === 0 && summary.wins === 1")
    assert page.locator("#roster tr").count() > 1
    assert page.evaluate("document.querySelector('#board').getContext('2d').getImageData(0,0,640,640).data.some((v,i)=>i%4===3&&v>0)")
    page.screenshot(path=str(ROOT / "target/replay-desktop.png"), full_page=True)
    page.locator("#time").evaluate("el => { el.value = 0; el.dispatchEvent(new Event('input')); }")
    page.locator("#play").click()
    page.wait_for_timeout(250)
    assert int(page.locator("#time").input_value()) > 0
    page.locator("#play").click()
    page.set_viewport_size({"width": 390, "height": 844})
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.screenshot(path=str(ROOT / "target/replay-mobile.png"), full_page=True)
    assert not errors, errors
    print(json.dumps({"desktop": "passed", "mobile": "passed", "playback": "passed", "errors": errors}))
    browser.close()
