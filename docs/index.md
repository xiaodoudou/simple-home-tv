---
title: Simple Home TV
body_class: home
permalink: /
---

<div class="hero">
  <img class="logo" src="assets/logo-512.png" alt="Simple Home TV logo">
  <h1>A calm home screen<br>for Android TV</h1>
  <p>Your apps and inputs over Apple's aerial videos, and nothing else unless you ask for it. Made for TVs that ship
  without Google Play too.</p>
  <div class="cta">
    <a class="btn primary" href="https://github.com/xiaodoudou/simple-home-tv/releases/latest">Download the APK</a>
    <a class="btn ghost" href="https://github.com/xiaodoudou/simple-home-tv">Source on GitHub</a>
  </div>
</div>

<div class="shot"><img src="assets/screenshots/home.jpg" alt="The home screen, with what Plex-style apps publish above the dock"></div>

<section>
  <h2>What it does</h2>
  <p class="lead">Everything is reachable with the arrows and OK, the way a TV remote wants it.</p>
  <div class="grid">
    <div class="card"><span class="tag">Home</span><h3>Your apps, icons only</h3><p>Pinned apps and HDMI inputs in a dock of one or two rows, over aerial videos in Full HD or 4K.</p></div>
    <div class="card"><span class="tag">What's on</span><h3>Continue watching</h3><p>Focus an app and the row above shows what it publishes. Focus an input and you see its live picture.</p></div>
    <div class="card"><span class="tag">All apps</span><h3>Down for everything else</h3><p>In your own order. Hold OK on a tile to add it to the home, move it, hide it or uninstall it.</p></div>
    <div class="card"><span class="tag">Screensaver</span><h3>Aerials</h3><p>Apple's aerial videos, with the time in a corner.</p></div>
  </div>
</section>

<section>
  <h2>Have a look</h2>
  <div class="gallery">
    <figure><img src="assets/screenshots/all-apps.jpg" alt="All apps"><figcaption>All apps, with the home's row on top</figcaption></figure>
    <figure><img src="assets/screenshots/move.jpg" alt="Moving a tile"><figcaption>Hold OK, pick Move, and the tile follows the arrows</figcaption></figure>
    <figure><img src="assets/screenshots/hold-menu.jpg" alt="The hold menu"><figcaption>Everything about a tile is one hold away</figcaption></figure>
    <figure><img src="assets/screenshots/settings.jpg" alt="Settings"><figcaption>Settings, including the TV's own Android settings</figcaption></figure>
  </div>
</section>

<section>
  <h2>TVs without Google Play</h2>
  <p class="lead">China-market Sony Bravia, Xiaomi TVs on their Chinese firmware and others ship without Google services. Most apps still run; a few need help.</p>
  <div class="grid">
    <div class="card"><span class="tag">Plex</span><h3>Plex that opens again</h3><p>The 2026 Plex builds crash on these TVs. <a href="https://github.com/xiaodoudou/plex-android-tv-no-play-services">A small patch</a> makes them start, and changes nothing where Google services exist.</p></div>
    <div class="card"><span class="tag">Apps</span><h3>Two stores, one each</h3><p>Aurora opens an app's Google Play page from Get; APKUpdater keeps apps updated with a remote-friendly layout.</p></div>
    <div class="card"><span class="tag">YouTube</span><h3>SmartTube</h3><p>No Google services needed and no ads. The home suggests it when Google Play is missing.</p></div>
  </div>
</section>

<div class="install">
  <h2>Install</h2>
  <p>Download the APK from the latest release, then with adb connected to the TV:</p>

<pre><code>adb install simple-home-tv.apk
adb shell cmd package set-home-activity com.simplehometv/.HomeActivity</code></pre>

  <p>Press Home on the remote. It needs Android TV 7 or later; the rows of what's on need Android 8.</p>
</div>
