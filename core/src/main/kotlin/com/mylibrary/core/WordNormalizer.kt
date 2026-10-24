package com.mylibrary.core

import java.text.Normalizer
import java.util.Locale

/**
 * قاعدة التطابق المعتمدة (الإصدار 1) — يجب أن تبقى مطابقة لما في server/app/normalize.py.
 *
 * - تُزال علامات الترقيم والمسافات وكل ما ليس حرفًا أو رقمًا («أحمد،» = «أحمد!» = «أحمد»).
 * - تُزال التشكيلة والتطويل فقط (الفتحة، الضمة، ـ ...) لأنها لا تغيّر الكلمة.
 * - لا يُوحَّد الألف/الهمزة/التاء المربوطة ولا تُفصل اللواصق:
 *   «ذهب» ≠ «يذهب»، و«الكتاب» ≠ «كتاب».
 * - الأحرف اللاتينية تُحوَّل إلى أحرف صغيرة.
 */
object WordNormalizer {
    const val VERSION = 1

    private val STRIP = Regex("[\\u0640\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")

    fun normalize(raw: String): String {
        val s = STRIP.replace(Normalizer.normalize(raw, Normalizer.Form.NFKC), "")
        val sb = StringBuilder(s.length)
        for (ch in s) if (Character.isLetterOrDigit(ch)) sb.append(ch)
        return sb.toString().lowercase(Locale.ROOT)
    }

    fun isUsable(raw: String): Boolean = normalize(raw).isNotEmpty()

    /** الصيغة المعروضة: تُحذف علامات الترقيم من الأطراف فقط مع بقاء التشكيل. */
    fun displayForm(raw: String): String = raw.trim().trim { ch ->
        !(Character.isLetterOrDigit(ch) || Character.getType(ch) == Character.NON_SPACING_MARK.toInt())
    }
}
