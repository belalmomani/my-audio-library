#!/usr/bin/env bash
# يشغّل اختبارات core بدون Maven باستخدام ملفات Gradle المرفقة (للبيئات المعزولة). في الوضع العادي استخدم: ./gradlew :core:test -PcoreOnly
set -e
L=${GRADLE_LIB:-/opt/gradle/lib}; HERE=$(cd "$(dirname "$0")" && pwd); OUT=$(mktemp -d)
KC=$(ls $L/kotlin-compiler-embeddable-*.jar $L/kotlin-stdlib-2*.jar $L/kotlin-script-runtime-*.jar $L/kotlin-reflect-*.jar $L/kotlin-daemon-embeddable-*.jar $L/trove4j-*.jar $L/annotations-*.jar $L/kotlinx-coroutines-core-jvm-*.jar | tr '\n' ':')
STD=$(ls $L/kotlin-stdlib-2*.jar | head -1); JU="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD:$JU" -d "$OUT" "$HERE/src/main/kotlin" "$HERE/src/test/kotlin"
java -cp "$OUT:$STD:$JU" org.junit.runner.JUnitCore com.mylibrary.core.CoreTest com.mylibrary.core.NormalizerTest com.mylibrary.core.IngestTests com.mylibrary.core.UploadTests com.mylibrary.core.FileTests com.mylibrary.core.AudioTests
