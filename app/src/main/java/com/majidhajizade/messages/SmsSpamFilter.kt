package com.majidhajizade.messages

import java.util.Locale

/**
 * Conservative, offline promotional SMS classifier.
 * False positives are reduced by exempting transactional/security messages
 * and requiring both a strong promotional phrase and additional evidence.
 */
internal object SmsSpamFilter {

    private val protectedTerms = listOf(
        "otp", "one time password", "verification code", "verify code",
        "security code", "authentication code", "login code",
        "password reset", "transaction", "your account", "account alert",
        "bank", "banking", "payment received", "payment successful",
        "payment failed", "receipt", "invoice", "withdrawal", "deposit",
        "رمز یکبار مصرف", "رمز یک‌بار مصرف", "رمز پویا", "کد تایید",
        "کد تأیید", "کد اعتبارسنجی", "کد ورود", "کد امنیتی",
        "تراکنش", "برداشت", "واریز", "پرداخت موفق", "پرداخت ناموفق",
        "موجودی", "حساب شما", "رمز عبور", "ورود به حساب"
    )

    private val strongPromotions = listOf(
        "طرح تعویض", "تعویض کالای کهنه", "خرید طلا", "فروش اقساطی",
        "تخفیف", "فروش ویژه", "پیشنهاد ویژه", "جشنواره فروش",
        "قرعه کشی", "قرعه‌کشی", "برنده شوید", "خرید اقساطی",
        "کد تخفیف", "تخفیف ویژه", "همین حالا خرید", "ثبت سفارش",
        "تبلیغات", "تبلیغاتی", "خرید کنید",
        "discount", "special offer", "limited offer", "sale",
        "promo code", "coupon", "buy now", "shop now",
        "subscribe now", "exclusive offer", "limited time offer"
    )

    private val actionTerms = listOf(
        "کلیک کنید", "اینجا کلیک", "همین حالا", "از دست ندهید",
        "لغو عضویت", "برای خرید", "ثبت نام کنید", "فعال‌سازی",
        "فعال سازی", "مشاهده پیشنهاد", "خرید آنلاین",
        "click here", "call now", "buy now", "unsubscribe",
        "sign up", "order now", "claim now", "visit"
    )

    fun isPromotional(sender: String, body: String): Boolean {
        if (body.isBlank()) return false

        val text = body.lowercase(Locale.ROOT)
        val normalized = text
            .replace('\u064a', '\u06cc')
            .replace('\u0643', '\u06a9')

        // Never automatically filter messages that look transactional,
        // account-related, financial, or security-sensitive.
        if (protectedTerms.any { normalized.contains(it) }) return false

        val promoCount = strongPromotions.count { normalized.contains(it) }
        if (promoCount == 0) return false

        val hasAction = actionTerms.any { normalized.contains(it) }
        val hasLink = Regex(
            """(?:https?://|www\.)\S+""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        val shortSender = sender.trim().matches(Regex("""\d{3,8}"""))

        // Two promotional phrases are sufficient. With only one phrase,
        // require an explicit action and corroborating sender/link evidence.
        // A link or a short sender alone can never trigger filtering.
        return promoCount >= 2 ||
            (promoCount == 1 && hasAction && (shortSender || hasLink))
    }
}
