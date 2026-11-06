# Stremio Auto prototype

## Install on another projector

1. Download **Luma-Stremio-Auto-Preview.apk** from the
   [Stremio Auto preview release](https://github.com/argeojerwin-svg/luma-launcher/releases/tag/stremio-auto-preview-2).
   This is a contributor preview based on upstream Luma 1.0.13, not an official
   upstream release. The ordinary upstream 1.0.13 APK does not include Auto.
2. Install the preview over the existing Luma app. It uses the same signing
   certificate and retains Luma's settings. Do not uninstall or clear Luma data.
   A PC/ADB can be used for setup, but is not needed afterward.
3. Open **Luma → Launcher settings → Stremio Auto → Add source manifest**.
   Enter the complete configured **HTTPS manifest.json URL** for each source
   you use. Obtain these from the source addon's own configuration/install page;
   an addon name, website address or magnet link is not sufficient. Add Torrentio
   and/or TPB if those are your sources. A failing source does not prevent another
   configured source from returning results. Source URLs can contain credentials:
   enter your own privately; do not paste them into GitHub issues or commits.
4. Switch **Auto recommendations** on. This starts the local service and its
   initial connection sample. **Refresh status** shows the latest result.
5. Select **Install Luma Auto in Stremio**. Luma copies and displays this
   projector's local installation address. Open Stremio and go to
   **Add-ons → Add addon**. Paste that complete address, or enter it as displayed,
   then choose **Add → Install**. Keep **http://**, not https://. Stremio's external
   install links incorrectly convert it to HTTPS for this local service.
6. Choose a movie or episode. On its source screen select the **Luma Auto** filter
   with Left/Right and OK, then **Auto · Best available**. The All filter also
   includes the recommendation, although it may be far along the horizontal list.
7. If it buffers, press Back and choose a source manually from Torrentio or another
   original filter. This version does not switch sources during playback.

The original sources must remain installed in Stremio for their manual choices.
Auto does not import or replace them automatically. Its source configuration is
device-wide and must be entered separately on each projector. Never reuse another
projector's local manifest URL: its random path belongs to that device. Stremio
may sync the addon entry to other account devices, but the service exists only
on the projector where it is enabled.

If Auto is missing, reopen Luma, check that Auto is On and sources are configured,
then restart Stremio and reopen the title. A title with no compatible results
does not show an Auto choice. If installation fails, check the complete local
HTTP address, including port and random path. If an Android keyboard fails to
open after installing an APK, reselect Luma Keyboard in Android input settings;
restart the projector if Android still cannot bind the keyboard service.

## How it works and its limits

Optional projector-hosted Stremio stream addon, bundled in Luma. Enable it in
Launcher settings → Stremio Auto, configure HTTPS source manifest URLs and install
Luma Auto in Stremio. Use Stremio's Add-ons → Add addon URL dialog with the HTTP
address copied by Luma's installation page. External `stremio://` install links
convert the address to HTTPS and do not work for this local service.
It listens only on 127.0.0.1:19765 under a private random URL.
No PC, root transport, remote host or separate player is used at runtime.

Auto returns one recommended original stream object, preserving its torrent hash,
file index, trackers, URL, headers and subtitle information. Existing addons and
manual source choices remain available. Empty, failed or incompatible provider
responses do not produce a fake playable stream. Requests support IMDb movies
and episodes; other identifiers currently return no recommendations.

Ranking uses a bounded 2 MB Cloudflare HTTP download on this projector (cached
for ten minutes), reported quality, reported peer counts, and an approximate
bitrate from file size plus Cinemeta runtime where available. Unknown bitrate
uses conservative quality-based estimates. Fifty-five percent of measured HTTP
throughput is used as the connection budget. If the speed sample fails, the
initial budget is based on 10 Mbps. This is not a torrent speed test. Reported
seed counts may be stale, and pack sizes/runtimes may be approximate. Candidate
downloads are not started while displaying recommendations.

The prototype limits automatic choices to 1080p SDR for the P6, deprioritises
camera copies, and avoids reported zero-peer torrents when alternatives exist.
If no compatible candidate exists, use another source from the original addons.
Returning to the same source list can reuse a recommendation for up to 30 seconds.
There is no playback interception, live source switching, automatic recovery
after playback starts, or promise of interruption-free playback. Selecting a new
movie in Stremio still requires selecting the Auto stream row.

Configuration is device-wide, opt-in, and private to Luma's app storage. Add-on
URLs may contain account credentials; never commit or publish them. Removing a
source affects Auto only, not Stremio's original addon installation. To remove
Auto, disable it in Luma and uninstall Luma Auto in Stremio. The local URL is
specific to this projector; account-synced installations will not work on other
devices without their own local service. Reopening Luma resumes an enabled
service if Android has stopped it. Android may restrict boot/background startup
on devices other than this P6.

Tests cover bandwidth/quality tradeoffs, reported peer availability, HDR/4K
exclusion, size/runtime bitrate estimation and camera-copy ranking. On-device
verification must additionally check local manifest installation, real provider
responses, the Auto row, and actual playback before calling the feature complete.

## P6 verification, 31 October 2026

Release build, release lint and all 62 existing/new unit tests passed (including
six ranking tests). The local HTTP manifest installed through Stremio's normal
Add addon dialog. Stremio displays a Luma Auto source filter containing one
Auto · Best available card, alongside the original source filters.

Auto selected a 720p Big Buck Bunny torrent using connection and peer estimates;
its peers connected but delivered no video data during the short test. This
confirms reported peers do not guarantee playback. A second test selected a
1080p Night of the Living Dead stream and actual video playback was visually
verified, with over 37 MB received. Both are public-domain/openly licensed test
titles. TPB's direct endpoint failed; the recommendation still completed using
Torrentio. The test player was closed afterward, Luma Keyboard remained selected,
and the temporary PC forwarding connections were removed. Boot recovery and
long-duration playback have not been tested for this prototype.

Protocol compatibility: output only `description`, removing an upstream `title`.
Stremio's deserializer treats `title` as an alias for `description`; including
both makes it reject the response. The original source's playback identity and
other transport fields are preserved.

## Next-episode autoplay

Auto streams now carry their own stable `behaviorHints.bingeGroup`:
`dev.luma.stremio.auto.v1`. Stremio can match Auto for the next episode even when
its chosen quality/provider changes. Original provider hints such as filename
and proxy headers remain intact. No other addon's binge group is reused.

After updating, select an Auto stream once from the source list. An episode
already started using the old build has no Auto binge group saved, so it may
still show source choices until selected again. Stremio's Auto-play next episode
setting must be enabled for end-of-episode progression. Manual Next Episode
matching also remains controlled by Stremio. If Auto returns no compatible source
or the provider fails, source selection/buffering can still occur.

## Startup recovery update, 3 November 2026

The on-device Auto setting and Stremio add-on entry can remain enabled while
Android has not started the local service. Startup previously ignored a failed
or null service start. Auto now checks readiness and makes up to five startup
attempts over bounded delays of 2, 5, 15, 30 and 60 seconds. Returning to Luma
also retries startup; disabling Auto cancels pending recovery. Startup exhaustion
is reported in Auto's status instead of silently presenting an enabled-but-dead
service. This does not require a PC or a new manifest installation.

Six additional tests cover rejected and accepted-but-not-ready starts, readiness,
disable/cancel, superseded callbacks and bounded exhaustion. All 68 unit tests,
release lint and the signed release build passed. These fixes are included in
preview 2; preview 1 does not contain them.

Device verification: the updated APK passed two consecutive projector restarts. On each restart Auto started as a foreground service and its unchanged local manifest answered before any manual service start or registration command. After removing PC forwarding, Stremio displayed the Luma Auto source filter. Existing source configuration, local URL and Next Episode identifier were preserved.

The user also confirmed that Next Episode starts the next Auto stream directly.
These on-device checks used the local 1.0.12-based build. The published preview
uses upstream 1.0.13 with the same Auto fixes and has separate build, unit-test,
lint and signing checks; that exact APK has not been installed for another
projector playback/reboot test.
