package com.n0n5ense.taassistant.core.grading

/**
 * 自分で書いたテストコード ([com.n0n5ense.taassistant.core.config.TestCase.testCode]) を、
 * コンパイルできる Java のソースにする。
 *
 * - `public class 名前` を含めばクラス全体として扱う (import やヘルパーメソッドも書ける)
 * - 含まなければ main の中身として扱い、[DEFAULT_CLASS_NAME] クラスの main で包む
 * - `package` 宣言が無ければ、提出されたクラスと同じパッケージに入れる (package-private のメソッドも呼べる)
 */
object TestCode {
    /** main の中身だけを書いたときのクラス名 */
    const val DEFAULT_CLASS_NAME = "TaaTest__"

    private val publicClassRegex =
        Regex("""\bpublic\s+(?:(?:final|abstract|strictfp)\s+)*class\s+([A-Za-z_$][\w$]*)""")

    /** コンパイルするテストコード */
    data class Prepared(val packageName: String?, val className: String, val source: String) {
        val mainClass: String get() = packageName?.let { "$it.$className" } ?: className
        val fileName: String get() = "$className.java"
    }

    /** @param studentPackage 提出されたクラスのパッケージ (無ければ null) */
    fun prepare(code: String, studentPackage: String?): Prepared {
        val text = code.replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
        val header = studentPackage?.let { "package $it;\n\n" }.orEmpty()
        val className = publicClassRegex.find(text)?.groupValues?.get(1)
            ?: return Prepared(
                studentPackage,
                DEFAULT_CLASS_NAME,
                buildString {
                    append(header)
                    append("public class $DEFAULT_CLASS_NAME {\n")
                    append("    public static void main(String[] args) throws Throwable {\n")
                    text.trimEnd().lines().forEach { append("        $it".trimEnd()).append('\n') }
                    append("    }\n")
                    append("}\n")
                },
            )
        val declared = parsePackageName(text)
        return if (declared != null) {
            Prepared(declared, className, text)
        } else {
            Prepared(studentPackage, className, header + text)
        }
    }
}
