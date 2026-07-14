<div align="center">

<img src="docs/assets/github-header.png" alt="Chirp - Parakeet Powered STT for Android" width="100%" />

**Local Parakeet transcription for Android, in a voice-notes app and a keyboard.**

</div>

Chirp is a hobby project from a guy who sort of knows what he's doing. I'm learning Android as I go, and AI helped with a lot of it, mostly Gemini.

Speech-to-text runs on your phone. Optional cleanup sends text to the cloud provider you choose.

<div align="center">
  <img src="docs/screenshots/home.png" alt="Chirp home screen with sample recordings" width="220" />
  <img src="docs/screenshots/details.png" alt="Chirp recording details with a sample transcript" width="220" />
  <img src="docs/screenshots/keyboard.png" alt="Chirp Voice keyboard input method" width="220" />
</div>

## What it does

- Records and plays voice notes, then transcribes them locally with Parakeet.
- Works as the **Chirp Voice** dictation keyboard and a home-screen widget.
- Can clean up text, make summaries, or chat about a transcript with your own API key.
- Has tags, profiles, word replacements, sharing, and Obsidian export.

## Download

Grab the APK from [GitHub Releases](https://github.com/hkay-dev/chirp-android/releases/latest). It needs Android 16 and downloads the speech model during setup.

I've only tested Chirp on a Galaxy S25 Ultra. If you try it on something else, I'd love to hear how it goes.

## Maybe someday

I'd like to try more Parakeet variants, Whisper and other speech models, local LLM cleanup on phones that can handle it, better cloud connections, easier sign-in alongside API keys, and modular ways to turn transcripts into useful stuff. Using a ChatGPT subscription would be interesting if that ever becomes an option.

I may not have the time, hardware, or skill to build all of that. I'm still curious.

## License

Chirp's code is [Apache 2.0](LICENSE). Credits include models.dev, AndroidLame-kotlin and LAME, sherpa-onnx and ONNX Runtime, NVIDIA's Parakeet model, and csukuangfj's conversion.

The full licenses and credits are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and bundled with the APK.
