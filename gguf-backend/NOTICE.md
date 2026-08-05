# GGUF backend notices

The stable backend builds against transcribe.cpp commit
`553f1099a2b3a5bc4421894be171f09960fc0f3a`, licensed under the MIT License.

The Parakeet TDT-CTC 110M model is published by NVIDIA under CC-BY-4.0 and
converted to GGUF by handy-computer. Chirp can download the Q8_0, Q6_K, or Q4_K_M
artifact from the handy-computer Hugging Face repository and verifies its size
and SHA-256 before activation.

- https://github.com/handy-computer/transcribe.cpp
- https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf
- https://huggingface.co/nvidia/parakeet-tdt_ctc-110m
