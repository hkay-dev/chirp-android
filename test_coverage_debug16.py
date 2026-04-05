import desloppify.languages.kotlin.test_coverage as kc
test_file = "app/src/test/java/dev/chirpboard/app/VoiceRecorderTest.kt"
prod_set = {"app/src/main/java/dev/chirpboard/app/VoiceRecorder.kt", "feature-keyboard/src/main/java/dev/chirpboard/app/feature/keyboard/recorder/VoiceRecorder.kt"}
mapped = kc.map_test_to_source(test_file, prod_set)
print(f"MAPPED FOR {test_file}: {mapped}")
