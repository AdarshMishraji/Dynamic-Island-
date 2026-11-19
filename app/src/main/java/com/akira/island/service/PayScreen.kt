package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.akira.island.core.tr
import com.akira.island.core.trf
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What is visible on the payment app screen: the phone payment screen, amounts and payment words.
 * Separate from screen reading, so the parsing can be tested without a phone.
 */
object PayScreenParser {
    private val spaces = Regex("[\\u00A0\\u202F\\u2007\\u2009]")

    /** An amount with thousands separators and decimals: "89,99", "1 250", "50.000", "12500.5" */
    private const val NUMBER = """\d{1,3}(?:[ .,]?\d{3})*(?:[.,]\d{1,2})?"""
    /** Currency after the amount: "350 ₽", "12.50 USD", "500р" */
    private const val CURRENCY = """₽|руб\.?|р\.?(?!\p{L})|RUB|RUR|USD|EUR|GBP|KZT|UAH|BYN|CNY|TRY|INR|IDR|PHP|MYR|THB|VND|BRL|""" +
        """AMD|GEL|UZS|AZN|KGS|€|£|₸|₴|₹|₱|₩|¥|฿|₫|₺|\$"""
    /** Currency before the amount: "$12.50", "Rp 50.000", "₹500" */
    private const val PREFIX = """[$€£₹₱₩¥฿₫₺₸]|R\$|Rp|RM|RUB|USD|EUR|INR|IDR|PHP|MYR|THB|KZT"""

    /** A charge on screen: "−89,99 ₽", "- 1 250 ₽", "–$12.50". A currency or decimals are required, otherwise it isn't money. */
    private val charge = Regex(
        """(?<![\p{L}\p{N}])[−–-]\s?(?:($NUMBER)\s?($CURRENCY)?|($PREFIX)\s?($NUMBER))""",
        RegexOption.IGNORE_CASE,
    )

    /** An amount with a currency anywhere in the text. It isn't preceded by a digit or an asterisk, since "*1234" is a card number. */
    private val amount = Regex(
        """(?<![\d*])$NUMBER\s?(?:$CURRENCY)|(?<!\p{L})(?:$PREFIX)\s?$NUMBER""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The phone payment screen: "Для оплаты поднесите телефон к терминалу", "Приложите смартфон к платёжному
     * терминалу", "Hold your phone up to the terminal", "Hold near reader", "Готово к оплате".
     */
    private val readyWords = Regex(
        "(подне|прилож|поднос|приклад)\\S*\\s+(\\S+\\s+){0,3}?к\\s+(\\S+\\s+)?(терминал|считыва|ридер)|" +
            "hold\\s+(\\S+\\s+){0,4}?(to|near|close to|against)\\s+(the\\s+)?(payment\\s+)?(terminal|reader)|" +
            "tap\\s+(\\S+\\s+){0,3}?(to|on)\\s+(the\\s+)?(terminal|reader)|ready to pay|готов\\S*\\s+к\\s+оплате",
        RegexOption.IGNORE_CASE,
    )

    /** Payment succeeded: this is how the result screen labels it. */
    private val paidWords = Regex(
        "оплачено|оплачена|оплата прошла|оплата выполнена|оплата совершена|оплата успешна|успешная оплата|покупка совершена|" +
            "покупка прошла|платёж выполнен|платеж выполнен|платёж прошёл|платеж прошел|payment successful|payment complete|" +
            "payment approved|\\bpaid\\b|аплачана|аплата прайшла|аплата выканана|плацёж выкананы|pembayaran berhasil|pago realizado|pagamento aprovado",
        RegexOption.IGNORE_CASE,
    )

    private val failureWords = Regex(
        "не прошл|не удал|отклон|отказ|ошибк|недостаточно|не хватает|не оплач|declined|failed|error|insufficient|unsuccess|try again|повтор",
        RegexOption.IGNORE_CASE,
    )

    /** All charges in a line, formatted for display: "89,99 ₽", "$12.50". */
    fun charges(text: String): List<String> {
        val t = normalize(text)
        return charge.findAll(t).mapNotNull { m ->
            val (num, cur, preCur, preNum) = m.destructured
            when {
                preNum.isNotEmpty() -> "$preCur$preNum"
                cur.isNotEmpty() -> "$num ${cur.trimEnd('.')}"
                num.contains(',') || num.contains('.') -> num
                else -> null
            }
        }.toList()
    }

    /** Spaces inside numbers (non-breaking, narrow) are made plain so that amounts are matched the same way. */
    fun normalize(text: String) = text.replace(spaces, " ")

    /** The first amount with a currency in the text: "89,99 ₽", "1 250 руб.", "$12.50", "Rp 50.000". */
    fun amount(text: String): String? = amount.find(normalize(text))?.value?.trim()

    /** The phone payment screen. */
    fun ready(text: String) = readyWords.containsMatchIn(normalize(text))

    /** "Оплачено", "Оплата прошла", and not "Оплата не прошла". */
    fun paid(text: String) = paidWords.containsMatchIn(text) && !failureWords.containsMatchIn(text)

    fun failure(text: String) = failureWords.containsMatchIn(text)
}

/**
 * The payment app screen while it is open. The island waits for two things:
 *  - the phone payment screen ("Hold your phone to the terminal", T-Pay, SberPay, Mir Pay, Yandex Pay...);
 *    then the island shows "Payment · To terminal", and it stays until you pay or leave;
 *  - a payment amount ("−89,99 ₽"); then right away "Paid", without waiting for the bank notification. Only a
 *    charge that wasn't on the screen when the payment screen appeared counts, and only if it is the only one on the screen,
 *    so the transaction history isn't taken for a payment. If the app itself opened already showing the result
 *    (you paid without opening it), "Paid" must also be on the screen.
 * Screen changes arrive only while such an app is open; it is parsed no more than once per 0.4 s.
 */
class PayScreenReader(private val service: AccessibilityService, private val scope: CoroutineScope) {
    val active: Boolean get() = current != null

    private var current: String? = null
    private var since = 0L
    private var readyAt = 0L
    private var baseline: MutableSet<String>? = null
    private var done = false
    /** The new texts from the last parse; we don't parse the same screen twice */
    private var lastFresh: List<String>? = null
    private var lastScan = 0L
    private var scanJob: Job? = null
    private var stopJob: Job? = null

    /** A new window: a different app starts over; a payment window in the same app is the payment screen. */
    fun onForeground(pkg: String, cls: String) {
        // A system window over the payment app: we keep reading when it goes away
        if (current != null && PayMonitor.overlay(pkg)) return
        if (pkg == current) {
            if (done && payWindow(cls)) {
                done = false
                readyAt = 0L
                baseline = null
                lastFresh = null
            }
            if (payWindow(cls)) markReady(pkg)
            schedule(150)
            return
        }
        stop()
        if (!PayMonitor.isPayApp(service, pkg)) return
        start(pkg)
        if (payWindow(cls)) markReady(pkg)
    }

    /** The Google services "Payment" window over any app: we read it for a minute while it is on screen. */
    fun onTapAndPay() {
        if (current != PayMonitor.GMS) {
            stop()
            start(PayMonitor.GMS)
        }
        markReady(PayMonitor.GMS)
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(60_000)
            if (current == PayMonitor.GMS) stop()
        }
    }

    fun onContentChanged(event: AccessibilityEvent) {
        if (event.packageName?.toString() != current) return
        schedule(if (readyAt != 0L) 150 else 400)
    }

    fun stop() {
        scanJob?.cancel()
        stopJob?.cancel()
        scanJob = null
        current = null
        baseline = null
    }

    private fun start(pkg: String) {
        val s = SettingsRepo.current
        if (!s.enabled || (!s.evPayReady && !s.evPaySuccess)) return
        current = pkg
        since = SystemClock.elapsedRealtime()
        readyAt = 0L
        baseline = null
        lastFresh = null
        done = false
        schedule(300)
    }

    /** Phone payment windows in some apps are literally called that: NFC, HCE, contactless payment. */
    private fun payWindow(cls: String) = PayWindow.containsMatchIn(cls)

    private fun markReady(pkg: String, texts: Set<String>? = null) {
        if (readyAt == 0L) {
            readyAt = SystemClock.elapsedRealtime()
            screenLogs = 0
            screenLoggedAt = 0L
            baseline = texts?.toMutableSet() ?: HashSet()
        }
        PayMonitor.onPayScreen(service, pkg)
    }

    private fun schedule(wait: Long) {
        if (scanJob?.isActive == true) return
        // No more than once per 0.4 s while waiting for the payment screen, since people scroll a bank app for a long time and we want to save battery
        val gap = if (readyAt != 0L) 0L else (400 - (SystemClock.elapsedRealtime() - lastScan)).coerceAtLeast(0)
        scanJob = scope.launch {
            delay(maxOf(wait, gap))
            runCatching { scan() }
        }
    }

    private fun scan() {
        val pkg = current ?: return
        val now = SystemClock.elapsedRealtime()
        lastScan = now
        // We wait for the payment after the payment screen for no more than three minutes; after that we don't parse the screen
        if (!done && readyAt != 0L && now - readyAt > PayMonitor.PAY_WINDOW_MS) {
            done = true
            return
        }
        val root = rootFor(pkg) ?: return
        val texts = LinkedHashSet<String>()
        collect(root, texts, 0)
        if (done) {
            // A second payment in the same wallet must not require closing/reopening the app.
            if (texts.any { PayScreenParser.ready(it) } && texts.none { PayScreenParser.paid(it) || PayScreenParser.failure(it) }) {
                done = false
                readyAt = 0L
                lastFresh = null
                markReady(pkg, texts)
            }
            return
        }

        // The phone payment screen: a hint in the island
        if (readyAt == 0L && texts.any { PayScreenParser.ready(it) }) {
            markReady(pkg, texts)
            return
        }
        val all = texts.flatMap { PayScreenParser.charges(it) }.distinct()
        if (readyAt != 0L) {
            val base = baseline ?: return
            // The payment screen is still being drawn: everything on it is "as it was", not a payment
            if (paymentBaselinePending(now - readyAt, base, texts)) {
                base += texts
                return
            }
            val fresh = texts.filter { it !in base }
            // The screen hasn't changed since last time, nothing to parse (while waiting for payment it changes often)
            if (fresh == lastFresh) return
            lastFresh = fresh
            logAfterReady(pkg, fresh, now)
            if (texts.any { PayScreenParser.failure(it) }) return
            val freshCharges = fresh.flatMap { PayScreenParser.charges(it) }.distinct()
            // For a wallet, an amount on the screen is the payment. A bank's screen may also have a transaction history,
            // so it also needs "Paid", or the payment screen must have been in view
            if (all.size == 1 && freshCharges.size == 1 &&
                texts.any { PayScreenParser.paid(it) }
            ) {
                return paid(pkg, freshCharges[0])
            }
            // The result screen is often without a minus: "Payment succeeded" and one new amount with a currency: "350 ₽"
            if (fresh.any { PayScreenParser.paid(it) }) {
                val sums = fresh.mapNotNull { PayScreenParser.amount(it) }.distinct()
                if (sums.size == 1) return paid(pkg, sums[0])
                // The screen says "payment succeeded" but has no amount: we show "Paid" without an amount
                if (sums.isEmpty()) return paid(pkg, "")
                PayDebug.add(trf("«Оплачено» на экране, но сумм: {0}", "“Paid” on screen, but amounts: {0}", sums.size), pkg, fresh.joinToString(" | "))
            }
        } else if (now - since < 4_000 && all.size == 1 && texts.any { PayScreenParser.paid(it) } && texts.none { PayScreenParser.failure(it) }) {
            // The app opened itself already showing the result: you paid without opening it
            paid(pkg, all[0])
        }
    }

    private var screenLogs = 0
    private var screenLoggedAt = 0L

    /**
     * For "Payment diagnostics": what appeared on screen after the payment screen. No more than once per two seconds and no
     * more than six entries per payment; long digit strings (card numbers) are hidden, kept in memory only.
     */
    private fun logAfterReady(pkg: String, fresh: List<String>, now: Long) {
        if (fresh.isEmpty() || screenLogs >= 6 || now - screenLoggedAt < 2_000) return
        screenLogs++
        screenLoggedAt = now
        val line = fresh.take(14).joinToString(" | ")
        PayDebug.add(tr("Экран после оплаты", "Screen after the payment screen"), pkg, line)
    }

    private fun paid(pkg: String, sum: String) {
        done = true
        PayMonitor.onScreenPaid(service, pkg, sum)
    }

    private fun rootFor(pkg: String): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?: runCatching { service.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg } } }.getOrNull()

    private fun collect(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 30 || out.size > 300) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 }?.let { out += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 }?.let { out += it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }

    private companion object {
        val PayWindow = Regex("nfc|hce|contactless|tapandpay|tap_and_pay|tappay|tpay|sberpay|mirpay", RegexOption.IGNORE_CASE)
    }
}
