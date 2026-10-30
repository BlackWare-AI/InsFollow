# Third-party components

InsFollow source is MIT licensed. Upstream components retain their own licenses:

- Kotlin standard library (JetBrains): Apache License 2.0, included in the APK. https://github.com/JetBrains/kotlin
- Gradle wrapper and generated startup scripts: Apache License 2.0. https://github.com/gradle/gradle
- Android platform APIs/System WebView are supplied by the device, not bundled as a browser engine.

The Apache 2.0 license is reproduced in LICENSES/Apache-2.0.txt and packaged under assets/licenses. Build/test tooling and test-only JUnit / JSON-java dependencies are declared in Gradle; they are not shipped as production application libraries.
