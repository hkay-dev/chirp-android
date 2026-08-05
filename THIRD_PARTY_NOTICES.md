# Third-party notices

Chirp is licensed under Apache License 2.0. That license covers Chirp's own source code only.
The components and assets below keep their original licenses and copyright ownership.

## Provider logos

The Anthropic, Cerebras, Google Gemini, Groq, and OpenAI provider logos were sourced from
[models.dev](https://github.com/anomalyco/models.dev). Models.dev publishes its repository under
the MIT License.

Copyright (c) 2025 models.dev

Full text: [licenses/MODELS.DEV-MIT.txt](licenses/MODELS.DEV-MIT.txt)

Provider names and logos may also be protected by trademark law. They are used only to identify
the matching service. The models.dev MIT license doesn't grant trademark rights.

## MP3 encoding

Chirp uses [AndroidLame-kotlin v0.0.4](https://github.com/banketree/AndroidLame-kotlin), which is
based on [TAndroidLame](https://github.com/naman14/TAndroidLame). The AndroidLame-kotlin repository
doesn't publish a separate license for its wrapper code. This notice records its origin but can't
grant rights that its authors haven't published.

AndroidLame-kotlin includes wrapper code derived from TAndroidLame. TAndroidLame's author licenses
that project under the GNU General Public License version 3 or, at the user's option, any later
version.

- Copyright (c) 2015 Naman Dwivedi
- Source: [naman14/TAndroidLame](https://github.com/naman14/TAndroidLame)
- Full license: [licenses/TANDROIDLAME-GPL-3.0-or-later.txt](licenses/TANDROIDLAME-GPL-3.0-or-later.txt)

The native encoder bundled by AndroidLame-kotlin is LAME 3.100. The LAME source headers grant use
under the GNU Library General Public License version 2 or, at the user's option, any later version.

- Project: [LAME MP3 Encoder](https://lame.sourceforge.io/)
- Corresponding source: [LAME 3.100 source archive](https://sourceforge.net/projects/lame/files/lame/3.100/)
- Full license: [licenses/LAME-LGPL-2.0-or-later.txt](licenses/LAME-LGPL-2.0-or-later.txt)

Chirp doesn't modify LAME. Recipients may replace or rebuild the separately packaged native
library subject to the Android platform and package-signing rules. Nothing in Chirp's Apache 2.0
license changes the rights granted by LAME's license.

Chirp's original source remains under Apache 2.0. Distribution of an APK containing the
TAndroidLame-derived wrapper must also follow the applicable GPL 3.0-or-later terms. This notice
doesn't resolve AndroidLame-kotlin's missing license for any added wrapper code that isn't covered
by TAndroidLame's license.

## On-device speech runtime

### transcribe.cpp, ggml, miniz, and KleidiAI

The default Parakeet 110M backend includes
[transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) at commit
`553f1099a2b3a5bc4421894be171f09960fc0f3a`, together with its bundled ggml and miniz code.
Arm builds also include [KleidiAI](https://github.com/ARM-software/kleidiai) v1.24.0.

- transcribe.cpp is MIT licensed. Full text:
  [licenses/TRANSCRIBE.CPP-MIT.txt](licenses/TRANSCRIBE.CPP-MIT.txt)
- ggml is MIT licensed. Full text: [licenses/GGML-MIT.txt](licenses/GGML-MIT.txt)
- miniz is MIT licensed. Full text: [licenses/MINIZ-MIT.txt](licenses/MINIZ-MIT.txt)
- KleidiAI is Apache 2.0 licensed. The Apache 2.0 text is included in
  [licenses/SHERPA-ONNX-APACHE-2.0.txt](licenses/SHERPA-ONNX-APACHE-2.0.txt)

### sherpa-onnx

Chirp includes sherpa-onnx 1.12.19 for on-device speech recognition.

- Copyright: k2-fsa and sherpa-onnx contributors
- Source: [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- License: Apache License 2.0
- Full text: [licenses/SHERPA-ONNX-APACHE-2.0.txt](licenses/SHERPA-ONNX-APACHE-2.0.txt)

### ONNX Runtime

The sherpa-onnx Android archive includes ONNX Runtime.

- Copyright (c) Microsoft Corporation
- Source: [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime)
- License: MIT
- Full text: [licenses/ONNX-RUNTIME-MIT.txt](licenses/ONNX-RUNTIME-MIT.txt)

## Speech model

Chirp defaults to the Q8_0 GGUF conversion from
[handy-computer/parakeet-tdt_ctc-110m-gguf](https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf).
The Q6_K and Q4_K_M conversions are optional. They come from
[NVIDIA Parakeet TDT-CTC 110M](https://huggingface.co/nvidia/parakeet-tdt_ctc-110m), which is
licensed under Creative Commons Attribution 4.0 International.

The optional larger model is
[sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8),
an ONNX INT8 conversion by [csukuangfj](https://huggingface.co/csukuangfj) of
[NVIDIA Parakeet TDT 0.6B V2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2).

- Original model creator: NVIDIA
- Converters and publishers: handy-computer for GGUF and csukuangfj for ONNX INT8
- Changes: converted to GGUF quantizations or sherpa-onnx ONNX INT8
- License: Creative Commons Attribution 4.0 International
- Full text: [licenses/CC-BY-4.0.txt](licenses/CC-BY-4.0.txt)

## Documentation font

The repository's header artwork source loads Google Sans Flex from Google Fonts. Google made
Google Sans Flex available under the SIL Open Font License 1.1 in November 2025. The font file
isn't stored in this repository.

- Source: [Google Fonts](https://fonts.google.com/)
- License information: [Google Fonts FAQ](https://developers.google.com/fonts/faq)

## Other dependencies

The remaining release dependency graph is made up of Apache-2.0 and MIT components. JUnit 4,
used only by tests, is licensed under Eclipse Public License 1.0. Every dependency keeps its own
license. Gradle module files contain the exact dependency coordinates and versions used by each
build.
