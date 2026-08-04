# Streaming transcription preview

Chirp uses a two-pass local transcription design.

## Authority

The continuous PCM capture is the source of truth. Parakeet TDT 0.6B performs the authoritative
final decode from that complete capture. Preview text is disposable and is never committed in
place of the final decode.

## First pass

The optional first pass is `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, using its int8
encoder, decoder, and joiner. The four runtime files total 43,649,301 bytes, about 41.6 MiB. Chirp
downloads them only on an unmetered connection and verifies their sizes and SHA-256 digests before
loading them.

The preview recognizer uses one low-priority CPU thread and a separate executor, model instance,
stream, and lock from Parakeet. Thermal status at `MODERATE` or above and Android power-save mode
pause first-pass decoding. New samples remain buffered in the stream for later preview decoding.
Audio capture and the final decode continue normally.

IME visibility starts model preparation but never opens or reserves the microphone. Each recording
feeds only newly persisted samples into a fresh `OnlineRecognizer` stream at roughly 320 ms
intervals.

## Fallback

If the optional model is missing, the network is metered, preparation fails, or native initialization
fails, Chirp omits live preview. It never falls back to preview work on Parakeet because that could
queue ahead of the authoritative decode. Preview failure never changes the saved PCM or the final
continuous Parakeet decode. Closing the IME requests native preview-model release once its active
stream closes.

## Final-model threads

Parakeet no longer hard-codes eight native threads. Chirp selects one or two threads on smaller or
low-RAM devices, three on six-core devices, and four on devices with eight or more available cores.
This keeps scheduling headroom for capture, the IME, and Android system work.
