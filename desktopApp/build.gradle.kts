import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.n0n5ense.taassistant.MainKt"

        nativeDistributions {
            // 実行するには OS ごとにその OS で作る (jpackage は他の OS 向けには作れない)
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TAAssistant"
            // CI では -PappVersion=1.0.<実行番号> を渡す
            packageVersion = (findProperty("appVersion") as String?) ?: "1.0.0"
            description = "Java の課題の採点を補助するツール"
            vendor = "n0n5ense"

            // 同梱する Java ランタイムに入れるモジュール (./gradlew :desktopApp:suggestRuntimeModules の結果に、
            // Excel 出力 (Apache POI) が使うことがあるものを足した)。
            // 採点に使う javac / java / javap は同梱のランタイムではなく、PATH か設定した JDK のものを使う
            modules(
                "java.instrument", "java.prefs", "java.security.jgss", "java.xml.crypto",
                "jdk.security.auth", "jdk.unsupported", "java.sql", "java.naming",
            )

            windows {
                menu = true
                menuGroup = "TAAssistant"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // バージョンを上げても同じアプリとして上書きインストールするための ID (変えないこと)
                upgradeUuid = "6f3b0f8e-4a52-4f5e-9d0b-2a7c1e9f4b31"
            }
            macOS {
                bundleID = "com.n0n5ense.taassistant"
            }
            linux {
                shortcut = true
            }
        }
    }
}
