# Dependency declaration coverage

All **56 catalog library declarations** in the baseline were audited. Versions below are selected component versions in the baseline runtime graph, not a claim that every component has retained APK classes. The linked reports explain use, transitive coupling and impact. Kotlin stdlib is implicit and covered in the core report. Build plugins and the compile-only stubs are covered there separately.

| Catalog alias | Maven coordinate | Declaration scope | FOSS / Store selected version | Audit / disposition |
| --- | --- | --- | --- | --- |
| `thor-extension-api` | `com.trinadhthatakula:thor-extension-api` | app: implementation | 3.0.0 / 3.0.0 | [Retain in its current scope](core-dependencies.md) |
| `asgard` | `com.trinadhthatakula:asgard` | app: implementation | 2.0.1 / 2.0.1 | [Retain in its current scope](ui-dependencies.md) |
| `odin` | `com.trinadhthatakula:odin` | app: else implementation | 1.1.0 / 1.1.0 | [Retain in its current scope](core-dependencies.md) |
| `accompanist-drawablepainter` | `com.google.accompanist:accompanist-drawablepainter` | app: implementation | 0.37.3 / 0.37.3 | [Remove direct declaration; Coil still requires it](ui-dependencies.md) |
| `androidx-core-ktx` | `androidx.core:core-ktx` | app: implementation, bypass: implementation | 1.19.1 / 1.19.1 | [Keep in app; remove unused bypass declaration](core-dependencies.md) |
| `androidx-datastore-preferences` | `androidx.datastore:datastore-preferences` | app: implementation | 1.2.1 / 1.2.1 | [Retain in its current scope](core-dependencies.md) |
| `androidx-documentfile` | `androidx.documentfile:documentfile` | app: implementation | 1.1.0 / 1.1.0 | [Retain in its current scope](core-dependencies.md) |
| `androidx-splashscreen` | `androidx.core:core-splashscreen` | app: implementation | 1.2.0 / 1.2.0 | [Retain in its current scope](ui-dependencies.md) |
| `junit` | `junit:junit` | app: testImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `robolectric` | `org.robolectric:robolectric` | app: testImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `androidx-junit` | `androidx.test.ext:junit` | app: testImplementation, app: androidTestImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `androidx-espresso-core` | `androidx.test.espresso:espresso-core` | app: androidTestImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `androidx-test-orchestrator` | `androidx.test:orchestrator` | app: androidTestUtil | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `androidx-biometric` | `androidx.biometric:biometric-ktx` | app: implementation | 1.4.0-alpha02 / 1.4.0-alpha02 | [Replace KTX with base biometric](ui-dependencies.md) |
| `androidx-activity-compose` | `androidx.activity:activity-compose` | app: implementation | 1.13.0 / 1.13.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-compose-bom` | `androidx.compose:compose-bom` | app: implementation, app: testImplementation, app: androidTestImplementation | 2026.09.00 / 2026.09.00 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-compose-animation` | `androidx.compose.animation:animation` | app: implementation | 1.13.0-alpha02 / 1.13.0-alpha02 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui` | `androidx.compose.ui:ui` | app: implementation | 1.13.0-alpha02 / 1.13.0-alpha02 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui-graphics` | `androidx.compose.ui:ui-graphics` | app: implementation | 1.13.0-alpha02 / 1.13.0-alpha02 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui-tooling` | `androidx.compose.ui:ui-tooling` | app: debugImplementation | not runtime / not runtime | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui-tooling-preview` | `androidx.compose.ui:ui-tooling-preview` | app: implementation | 1.13.0-alpha02 / 1.13.0-alpha02 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui-test-manifest` | `androidx.compose.ui:ui-test-manifest` | app: debugImplementation | not runtime / not runtime | [Retain in its current scope](ui-dependencies.md) |
| `androidx-ui-test-junit4` | `androidx.compose.ui:ui-test-junit4` | app: testImplementation, app: androidTestImplementation | not runtime / not runtime | [Retain in its current scope](ui-dependencies.md) |
| `androidx-material3` | `androidx.compose.material3:material3` | app: implementation | 1.5.0-alpha29 / 1.5.0-alpha29 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-material-icons-extended` | `androidx.compose.material:material-icons-extended` | app: implementation | 1.7.8 / 1.7.8 | [Retain extended icons per maintainer decision](ui-dependencies.md) |
| `androidx-navigation3-runtime` | `androidx.navigation3:navigation3-runtime` | app: implementation | 1.2.0 / 1.2.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-navigation3-ui` | `androidx.navigation3:navigation3-ui` | app: implementation | 1.2.0 / 1.2.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-lifecycle-runtime-ktx` | `androidx.lifecycle:lifecycle-runtime-ktx` | app: implementation | 2.11.0 / 2.11.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-lifecycle-runtime-compose` | `androidx.lifecycle:lifecycle-runtime-compose` | app: implementation | 2.11.0 / 2.11.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-lifecycle-viewmodel-compose` | `androidx.lifecycle:lifecycle-viewmodel-compose` | app: implementation | 2.11.0 / 2.11.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-lifecycle-viewmodel-navigation3` | `androidx.lifecycle:lifecycle-viewmodel-navigation3` | app: implementation | 2.11.0 / 2.11.0 | [Retain in its current scope](ui-dependencies.md) |
| `room-runtime` | `androidx.room:room-runtime` | app: implementation | 2.8.5 / 2.8.5 | [Retain in its current scope](core-dependencies.md) |
| `room-ktx` | `androidx.room:room-ktx` | app: implementation | 2.8.5 / 2.8.5 | [Remove empty compatibility artifact](core-dependencies.md) |
| `room-compiler` | `androidx.room:room-compiler` | app: ksp | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `room-testing` | `androidx.room:room-testing` | app: androidTestImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `kotlinx-serialization-json` | `org.jetbrains.kotlinx:kotlinx-serialization-json` | app: implementation | 1.11.0 / 1.11.0 | [Retain in its current scope](core-dependencies.md) |
| `kotlinx-coroutines-android` | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | app: implementation | 1.11.0 / 1.11.0 | [Retain in its current scope](core-dependencies.md) |
| `kotlinx-coroutines-test` | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | app: testImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `turbine` | `app.cash.turbine:turbine` | app: testImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `lottie-compose` | `com.airbnb.android:lottie-compose` | app: implementation | 6.7.1 / 6.7.1 | [Retain in its current scope](ui-dependencies.md) |
| `shizuku-api` | `dev.rikka.shizuku:api` | app: implementation | 13.1.5 / 13.1.5 | [Retain in its current scope](core-dependencies.md) |
| `shizuku-provider` | `dev.rikka.shizuku:provider` | app: implementation | 13.1.5 / 13.1.5 | [Retain in its current scope](core-dependencies.md) |
| `dhizuku-api` | `io.github.iamr0s:Dhizuku-API` | app: implementation | 2.6.0 / 2.6.0 | [Retain in its current scope](core-dependencies.md) |
| `koin-android` | `io.insert-koin:koin-android` | app: implementation | 4.2.2 / 4.2.2 | [Retain in its current scope](core-dependencies.md) |
| `koin-androidx-compose` | `io.insert-koin:koin-androidx-compose` | app: implementation | 4.2.2 / 4.2.2 | [Retain in its current scope](core-dependencies.md) |
| `koin-annotations` | `io.insert-koin:koin-annotations` | app: implementation | 4.2.2 / 4.2.2 | [Retain in its current scope](core-dependencies.md) |
| `koin-androidx-workmanager` | `io.insert-koin:koin-androidx-workmanager` | app: implementation | 4.2.2 / 4.2.2 | [Retain in its current scope](core-dependencies.md) |
| `androidx-work-runtime` | `androidx.work:work-runtime` | app: implementation | 2.12.0 / 2.12.0 | [Retain in its current scope](core-dependencies.md) |
| `androidx-work-testing` | `androidx.work:work-testing` | app: androidTestImplementation | not runtime / not runtime | [Retain in its current scope](core-dependencies.md) |
| `coil-compose` | `io.coil-kt.coil3:coil-compose` | app: implementation | 3.6.3 / 3.6.3 | [Retain in its current scope](ui-dependencies.md) |
| `play-billing` | `com.android.billingclient:billing` | app: storeImplementation | not runtime / 9.1.0 | [Retain in its current scope](core-dependencies.md) |
| `play-billing-ktx` | `com.android.billingclient:billing-ktx` | app: storeImplementation | not runtime / 9.1.0 | [Retain in its current scope](core-dependencies.md) |
| `androidx-adaptive` | `androidx.compose.material3.adaptive:adaptive` | app: implementation | 1.3.0 / 1.3.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-adaptive-layout` | `androidx.compose.material3.adaptive:adaptive-layout` | app: implementation | 1.3.0 / 1.3.0 | [Retain in its current scope](ui-dependencies.md) |
| `androidx-adaptive-navigation` | `androidx.compose.material3.adaptive:adaptive-navigation` | app: implementation | 1.3.0 / 1.3.0 | [Remove direct declaration; navigation3 adapter still requires it](ui-dependencies.md) |
| `androidx-adaptive-navigation3` | `androidx.compose.material3.adaptive:adaptive-navigation3` | app: implementation | 1.3.0 / 1.3.0 | [Retain in its current scope](ui-dependencies.md) |
