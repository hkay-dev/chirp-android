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

The native encoder bundled by AndroidLame-kotlin is LAME 3.100. The LAME source headers grant use
under the GNU Library General Public License version 2 or, at the user's option, any later version.

- Project: [LAME MP3 Encoder](https://lame.sourceforge.io/)
- Corresponding source: [LAME 3.100 source archive](https://sourceforge.net/projects/lame/files/lame/3.100/)
- Full license: [licenses/LAME-LGPL-2.0-or-later.txt](licenses/LAME-LGPL-2.0-or-later.txt)

Chirp doesn't modify LAME. Recipients may replace or rebuild the separately packaged native
library subject to the Android platform and package-signing rules. Nothing in Chirp's Apache 2.0
license changes the rights granted by LAME's license.

## On-device speech runtime

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

Chirp downloads
[sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8),
an ONNX INT8 conversion by [csukuangfj](https://huggingface.co/csukuangfj) of
[NVIDIA Parakeet TDT 0.6B V2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2).

- Original model creator: NVIDIA
- Converter and publisher: csukuangfj
- Changes: converted to sherpa-onnx ONNX format and quantized to INT8
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
