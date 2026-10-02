# Worship Recorder

An Android app for recording worship songs (made with Telugu Christian songs in mind) over
background music, **stretching the song as long as you are led**. Repeat a line, the Pallavi or a
Charanam as many times as you like while you sing, so a 5-minute song can become a 10–15 minute
time of worship, and the music always stays in time.

| Home | Mark sections | Rhythm & pads | Ready to sing | Singing | Mix & share |
|---|---|---|---|---|---|
| ![](docs/screenshots/1_home.png) | ![](docs/screenshots/2_edit_track.png) | ![](docs/screenshots/3_edit_rhythm.png) | ![](docs/screenshots/4_sing_start.png) | ![](docs/screenshots/5_sing_live.png) | ![](docs/screenshots/6_mix.png) |

## Sing with ready music (no setup)

Tap **Sing with ready music**, choose a style (Slow worship, Bhajan 6/8, Keherwa praise, Dadra
devotional, Praise band 4/4, or Tanpura & keys only), tap your key (Sa), and **Start singing**.
By default the keys hold one steady Sa–Pa chord, which fits any song; switch on *Chord changes*
for a simple Pallavi/Charanam pattern. The music keeps going for as long as you sing.

## Your own background music

1. **Import a track**: any karaoke/instrumental MP3, M4A, WAV, etc. from your phone. Play it once
   and tap **Mark section here** where each part begins (Intro, Pallavi, Interlude, Charanam 1, …,
   or a single line you like to repeat). Optional: tap the beat and **Snap markers** so every
   repeat starts exactly on the beat. Use the ⟳ button on a section to hear its loop seam.
2. **Rhythm & pads**: the app makes the accompaniment itself: tabla/dholak (Keherwa, Dadra,
   Bhajan 6/8) or a kit (Slow worship 4/4, Pop 4/4), keyboard pads, bass and tanpura, in any key
   and tempo. Type chords per section, one per
   bar: `C C F G`. Split a bar with a comma (`F,G`), `-` holds the last chord, `N` = drums only.
   **Transpose** moves everything to suit your voice.

   **Real tabla/dholak sound:** tap *Load sample pack* and pick a few short `.wav` hits. The file
   name says which sound it is: `dha`, `dhin`, `na`, `tin`, `ti`, `ge`, `ka` (also `kick`, `snare`,
   `hat`/`shaker` for the kit rhythms), e.g. `Dholak_Ge-02.wav`. Any sound you don't provide uses
   the built-in synthesized one, and Dha/Dhin are made from Ge + Na/Tin if only those are given.

## Singing

Wear **headphones** (wired is best) so the mic only hears your voice. Pick where to start, tap
**Record**, and while you sing:

- **Repeat**: keep looping the current part until you tap **Next**.
- **×2 / ×4**: sing this part that many more times, then move on automatically.
- **Next**: move on when the current part finishes.
- Tap any **part name** to go there after the current pass (e.g. back to the Pallavi).
- **Fade out** ends the music gently; **Stop & save** finishes the recording.

Each section has a **Repeat until Next** switch, so the Pallavi and Charanams can loop by default
while intros and interludes play through. Lyrics (Telugu or English) can be added per section and
are shown large while you sing.

Repeats always happen at a section boundary, with a 10 ms crossfade for imported tracks, so they
never sound chopped.

Before recording you can turn on **Hear my voice** (your voice in the headphones; there is a
small delay, so it's optional) and run **Sync calibration** once per pair of headphones: hold an
earbud against the phone's mic and the app measures the exact delay from a few clicks. Every new
recording with those headphones then starts perfectly in sync.

## Mixing and sharing

Your voice and the music are recorded separately, so after recording you can change the music
and voice volume, add reverb, and fine-tune **voice sync** (the app lines the voice up
automatically using Android's audio timestamps; the slider corrects any remaining delay, e.g.
with Bluetooth headphones). Then **Save M4A** (small, good for WhatsApp) or **Save WAV** (full
quality). Files go to *Music › Worship Recorder* and can be shared directly.

## Install

Download `app-release.apk` from the latest **Build APK** run under the repository's *Actions* tab
(artifact `WorshipRecorder-apk`), open it on your phone and allow installing from this source.
Android 8.0 or newer.

## Build

```
./gradlew :app:assembleRelease       # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest     # unit tests, demo audio (app/build/demo) and screenshots
```

## Code map

- `core/`: plain Kotlin, unit-tested without a device
  - `LoopEngine`: plays sections and decides at each section end what comes next
    (repeat / ×N / next / jump / fade-out)
  - `TrackSource`: imported track split at markers; `Resampler`; marker snapping
  - `Synth`: generated tabla/dholak, pads, bass and Karplus-Strong tanpura
  - `Mixer`: voice + music with sync offset, high-pass, Freeverb reverb, soft limiter
  - `Calibration`: click track and loopback delay measurement
- `audio/`: Android audio: `AudioRunner` (AudioTrack + AudioRecord, stems, sync measurement),
  `Decoder` (MediaCodec import), `Exporter` (M4A/WAV), `MixPlayer`, `RecordingService`
- `ui/`: Jetpack Compose screens

## Ideas for later

- Pitch/key change for imported tracks (needs a time-stretch/pitch-shift library)
- Waveform zoom for very precise markers
- More rhythms (Rupak, Teentaal, 6/8 waltz) and fills before section changes
- Count-in click before recording
- Zero-delay voice monitoring via native audio (Oboe/AAudio)
