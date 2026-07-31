plugins {
    `java-library`
}

// Must match `:app` and `:bypass` (both 21). The IDE derives ONE project-wide language level and
// bytecode target from the Gradle model, so a module that disagrees drags the whole project down to
// its level and rewrites `.idea/compiler.xml` and `.idea/misc.xml` on every sync — which is why
// hand-editing those files back to 21 never held. Nothing here ships: `:bypass` takes this module as
// `compileOnly`, so these stubs only satisfy the compiler for symbols ART already provides.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// `sun.misc` belongs to the JDK's `jdk.unsupported` module, and from -source 9 onwards javac refuses
// to compile a source file into a package a system module already owns. At -source 8 that check does
// not run, which is the only reason this module ever compiled at 1.8. `--patch-module` is the
// sanctioned way to say "these sources patch that module" — it is what lets the shadow stub keep its
// required `sun.misc.Unsafe` name. Android's android.jar does not ship `sun.misc.Unsafe`, so `:bypass`
// still resolves the symbol from here and not from the JDK.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf("--patch-module", "jdk.unsupported=${projectDir.resolve("src/main/java")}")
    )
}
