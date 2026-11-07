package com.n0n5ense.taassistant.core.grading

/**
 * main から直接呼んでいるメソッドの確認 ([com.n0n5ense.taassistant.core.config.TestCase.calls])。
 *
 * コンパイル済みのクラスを `javap -c -p` で逆アセンブルし、main の中の呼び出し命令を調べる。
 * - main から呼んだ自作メソッドの中までは辿らない (直接の呼び出しだけ)
 * - main の中に書いたラムダの本体 (コンパイラが `lambda$main$0` などに分ける) は main の一部として数える
 * - 呼び出しは「パッケージ付きのクラス名.メソッド名」で表す (コンストラクタは `クラス名.<init>`)
 */
object CallCheck {
    /** `// Method java/util/Arrays.sort:([I)V` や `// InterfaceMethod java/util/List.add:(...)Z` */
    private val invokeRegex = Regex("""//\s*(?:Interface)?Method\s+(.+?):\(""")
    private val lambdaOfMain = Regex("""\blambda\${'$'}main\${'$'}\d+\(""")

    /**
     * `javap -c -p` の出力から、main (とその中のラムダ) で呼んでいるメソッドを、現れた順に重複なく返す。
     * main が無ければ null。
     * @param className 逆アセンブルしたクラスのパッケージ付きの名前 (同じクラスのメソッドはクラス名が省略されるため)
     */
    fun parseJavap(output: String, className: String): List<String>? {
        val calls = linkedSetOf<String>()
        var foundMain = false
        var inTarget = false
        for (line in output.replace("\r", "").lines()) {
            // メソッドの宣言は2文字の字下げ (命令はもっと深い)
            if (line.startsWith("  ") && !line.startsWith("   ") && line.trimEnd().endsWith(";") && '(' in line) {
                val isMain = " main(java.lang.String[])" in line || " main(java.lang.String...)" in line
                if (isMain && " static " in " $line") foundMain = true
                inTarget = (isMain && " static " in " $line") || lambdaOfMain.containsMatchIn(line)
                continue
            }
            if (!inTarget) continue
            val target = invokeRegex.find(line)?.groupValues?.get(1)?.replace("\"", "") ?: continue
            val dot = target.lastIndexOf('.')
            val call = if (dot < 0) {
                "$className.$target"
            } else {
                target.substring(0, dot).replace('/', '.') + "." + target.substring(dot + 1)
            }
            // ラムダ本体の呼び出しはコンパイラが作ったものなので数えない
            if (!lambdaOfMain.containsMatchIn("$call(")) calls += call
        }
        return if (foundMain) calls.toList() else null
    }

    /**
     * 呼び出し [call] (パッケージ付きのクラス名.メソッド名) が、指定 [required] に当たるか。
     * - `sort`: メソッド名だけ
     * - `Arrays.sort` / `java.util.Arrays.sort`: クラス名 (パッケージは省略可) とメソッド名
     * - `new ArrayList` / `ArrayList.<init>`: コンストラクタ
 * - `Files.*`: そのクラスのすべてのメソッド
     * 末尾の `()` は無視する
     */
    fun matches(required: String, call: String): Boolean {
        var spec = required.trim().removeSuffix("()").trim()
        if (spec.startsWith("new ")) spec = spec.removePrefix("new ").trim() + ".<init>"
        if (spec.isEmpty()) return false
        val callDot = call.lastIndexOf('.')
        val callOwner = call.substring(0, callDot.coerceAtLeast(0))
        val callName = call.substring(callDot + 1)
        val specDot = spec.lastIndexOf('.')
        if (specDot < 0) return callName == spec
        val specOwner = spec.substring(0, specDot)
        val specName = spec.substring(specDot + 1)
        // `クラス.*` はそのクラスのすべてのメソッド
        if (specName != "*" && callName != specName) return false
        // 入れ子のクラスは javap では Outer$Inner になる
        val owner = callOwner.replace('$', '.')
        return owner == specOwner || owner.endsWith(".$specOwner")
    }

    /** [required] のうち、[calls] のどれにも当たらないもの */
    fun missing(required: List<String>, calls: List<String>): List<String> =
        required.filter { spec -> calls.none { matches(spec, it) } }
}
