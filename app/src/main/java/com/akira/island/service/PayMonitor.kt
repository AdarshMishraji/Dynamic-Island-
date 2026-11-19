package com.akira.island.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.SystemClock
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import com.akira.island.core.IslandBus
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.PayReady
import com.akira.island.core.Tints
import com.akira.island.R
import com.akira.island.core.tr
import com.akira.island.core.trf
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Contactless payment by phone, like Apple Pay on iPhone. The "Payment · To terminal" hint appears
 * only when the payment app shows the pay-by-phone screen ("Hold your phone to the terminal")
 * or the Google services "Payment" window pops up, not when you merely open the bank. It stays until
 * the payment goes through or you leave the app. "Paid" with the amount comes from the app screen
 * (PayScreenReader), a bank notification, or an SMS from its number.
 */
object PayMonitor {
    /** Known banks and wallets, in case the system doesn't return the list of payment services. */
    private val known = setOf(
        "com.google.android.apps.walletnfcrel", // Google Wallet / Google Pay
        "com.samsung.android.spay", // Samsung Wallet
        "ru.nspk.mirpay", // Mir Pay
        "com.idamob.tinkoff.android", // T-Bank (T-Pay)
        "ru.alfabank.mobile.android", // Alfa-Bank (Alfa Pay)
        "ru.sberbankmobile", // Sberbank (SberPay)
        "ru.vtb24.mobilebanking.android", // VTB
        "ru.raiffeisennews", // Raiffeisen
        "ru.gazprombank.android.mobilebank.app", // Gazprombank
        "ru.sovcomcard.halva.v1", // Halva
        "ru.mts.money", // MTS Bank
        "com.paypal.android.p2pmobile",
        "com.revolut.revolut",
        "com.belkartpay.belkartpay",
    )

    /** Wallets: if they show an amount on their own screen, that is the payment. */
    private val wallets = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
        "ru.nspk.mirpay",
    )

    /**
     * Three minutes: how long "To terminal" stays, how long we wait for payment after the payment screen,
     * and how long the same amount isn't shown a second time.
     */
    const val PAY_WINDOW_MS = 180_000L

    /** The "Payment" window itself from Google services: appears when the phone is held to a terminal. */
    const val GMS = "com.google.android.gms"

    /** System windows over the payment app (Google windows, the NFC "Pay with...?" question): the user hasn't left the app. */
    fun overlay(pkg: String) = pkg == GMS || pkg == "com.android.nfc"

    /** Notifications from these apps are also parsed as payments: banks that aren't in the list above. */
    private val bankish = Regex(
        "bank|banking|sber|tinkoff|tbank|vtb|alfa|raiff|sovcom|halva|gazprom|psbank|otkritie|pochta|rshb|uralsib|" +
            "rosbank|akbars|mkb|yoomoney|qiwi|wallet|\\.pay|pay\\.|mirpay|spay|fintech|" +
            "kaspi|halyk|revolut|paypal|gopay|paytm|phonepe|gcash|belkart|priorbank|mtbank|belinvest|belarus",
        RegexOption.IGNORE_CASE,
    )

    /** The SMS sender is a bank: a short number (900) or a name. */
    private val bankSender = Regex(
        "^\\d{3,6}$|bank|банк|sber|сбер|tinkoff|т-банк|t-bank|vtb|втб|alfa|альфа|raiff|райф|gazprom|газпром|sovcom|совком|" +
            "pochta|почта|otkritie|открытие|mts|мтс|ozon|озон|psb|псб|rshb|россельхоз|uralsib|уралсиб|rosbank|росбанк|halva|халва|" +
            "yoomoney|юmoney",
        RegexOption.IGNORE_CASE,
    )

    private var readyPkg: String? = null
    /**
     * When and in which app "To terminal" was last shown: an amount in a notification from this app,
     * a wallet or a bank SMS right after that is that payment. After "Paid" the window closes.
     */
    private var readyShownAt = 0L
    private var readyShownPkg: String? = null
    private var readyTimeout: Job? = null
    private var confirmedReadyPkg: String? = null
    private var readyWallAt = 0L
    private var paymentSession = 0L
    private val payments = PaymentDeduplicator()

    /** Apps that can pay by phone (they declare a card emulation service, HCE), taken from the system. */
    private var nfcApps: Set<String> = emptySet()
    private var nfcCheckedAt = 0L

    /** Whether the app can pay by phone: Mir Pay, T-Pay, SberPay, Yandex Pay and any others. */
    fun isPayApp(context: Context, pkg: String): Boolean = pkg in known || pkg in nfcApps(context)

    /** The list of payment apps rarely changes, so we ask the system once every ten minutes. */
    private fun nfcApps(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (nfcCheckedAt == 0L || now - nfcCheckedAt > 10 * 60_000) {
            nfcCheckedAt = now
            nfcApps = runCatching {
                context.packageManager.queryIntentServices(Intent(HostApduService.SERVICE_INTERFACE), 0)
                    .mapNotNull { it.serviceInfo?.packageName }
                    .toSet() - context.packageName - "com.android.nfc"
            }.getOrDefault(emptySet())
        }
        return nfcApps
    }

    /** Whose icon to show: for the Google window, Google Wallet. */
    private fun appFor(pkg: String) = if (pkg == GMS) "com.google.android.apps.walletnfcrel" else pkg

    /**
     * The screen shows a phone payment: a hint in the island until you pay or leave the app.
     * Just in case (if we don't learn about leaving), it goes away on its own after three minutes.
     */
    fun onPayScreen(context: Context, pkg: String, confirmed: Boolean = true) {
        val s = SettingsRepo.current
        if (!s.enabled) return
        // Recognition must still work when the visual “Hold near reader” banner is disabled.
        // Merely opening a bank is never evidence that a later transfer was a phone payment.
        if (confirmed && (confirmedReadyPkg != pkg || readyShownAt == 0L)) {
            paymentSession++
            confirmedReadyPkg = pkg
            readyShownAt = SystemClock.elapsedRealtime()
            readyWallAt = System.currentTimeMillis()
            readyShownPkg = appFor(pkg)
            PayDebug.add(tr("Экран оплаты", "Payment screen"), pkg)
        }
        readyPkg = pkg
        if (!s.evPayReady || IslandBus.payReady.value?.pkg == pkg) return
        IslandBus.payReady.value = PayReady(pkg, NotificationParser.icon(context, appFor(pkg)))
        readyTimeout?.cancel()
        readyTimeout = IslandBus.scope.launch {
            delay(PAY_WINDOW_MS)
            clearReady()
        }
    }

    /** The "Payment" window from Google services: the phone was held to a terminal. */
    fun onTapAndPay(context: Context) = onPayScreen(context, GMS)

    /** The payment app was opened. With "Hint on open" shown right away, as before; without it we wait for the payment screen. */
    fun onAppOpened(context: Context, pkg: String) {
        if (SettingsRepo.current.payHintOnOpen && isPayApp(context, pkg)) onPayScreen(context, pkg, confirmed = false)
    }

    /** Another app was opened: the user left without paying, so the hint is no longer needed. */
    fun onForeground(pkg: String) {
        val ready = readyPkg ?: return
        if (pkg == ready || overlay(pkg)) return
        clearReady()
    }

    /** Remove the hint: paid, left the app, or the screen went off. */
    fun clearReady() {
        readyPkg = null
        confirmedReadyPkg = null
        readyTimeout?.cancel()
        readyTimeout = null
        IslandBus.payReady.value = null
    }

    private val success = Regex(
        "покупк|оплат|списан|списал|paid|payment|purchase|charged|spent|" +
            "pembayaran|transaksi|pago|compra|pagamento|zahlung|achat|paiement|ödeme|satın",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Not a payment: top-ups, transfers, refunds, failed payments, and also reminders and bills
     * ("К оплате", "Не забудьте оплатить", "будет списано", "Payment due").
     */
    private val skip = Regex(
        "пополн|зачисл|поступил|входящ|получен|вам перев|кэшбэк|кешбэк|cashback|возврат|refund|отклон|отказ|declined|не удал|ошибк|" +
            "не прошл|не выполн|недостаточно|не хватает|не оплач|к оплате|на оплату|оплатит|заплатит|" +
            "напомина|будет списан|спишем|спишется|предстоящ|ожидает|обработке|pending|scheduled|due|remind|failed|unsuccessful|insufficient|request|received",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Перевод" ("Transfer") is not a payment if there was no phone payment screen before it. But right after the payment screen
     * the payment itself is sometimes labeled that way (SBP, QR), so then the word doesn't get in the way.
     */
    private val transfer = Regex("перевод|transfer|transferred", RegexOption.IGNORE_CASE)

    private fun showPaid(context: Context, sum: String, caption: String, pkg: String) {
        clearReady()
        IslandBus.post(
            Notice(
                title = tr("Оплачено", "Paid"),
                state = sum.ifBlank { null },
                icon = Ios.load(context, R.drawable.ios_checkmark_circle),
                tint = Tints.Green,
                caption = caption,
                style = NoticeStyle.PAY_DONE,
                appIcon = NotificationParser.icon(context, pkg),
                extraMs = 900,
            ),
        )
    }

    /** The payment app showed a checkmark and an amount on its screen. */
    fun onScreenPaid(context: Context, pkg: String, sum: String) {
        val s = SettingsRepo.current
        PayDebug.add(trf("С экрана: оплачено {0}", "From the screen: paid {0}", sum), pkg)
        if (!s.enabled || !s.evPaySuccess) return
        val now = SystemClock.elapsedRealtime()
        val session = if (readyShownPkg == appFor(pkg) && now - readyShownAt in 0 until PAY_WINDOW_MS) paymentSession else 0L
        if (payments.duplicate(sum, "screen:$pkg:$session", session, now)) return
        val app = appFor(pkg)
        showPaid(context, sum, NotificationParser.appLabel(context, app), app)
    }

    /**
     * A notification from a bank, wallet or bank SMS about a payment. Returns true if it was shown
     * as "Paid" (or was already shown from the screen) and doesn't need to be shown as a regular message card.
     */
    fun onNotification(context: Context, sbn: StatusBarNotification): Boolean {
        val s = SettingsRepo.current
        if (!s.enabled || !s.evPaySuccess || sbn.isOngoing) return false
        val pkg = sbn.packageName
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val ex = n.extras ?: return false
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val sms = pkg == defaultSms(context)
        if (sms) {
            if (!bankSender.containsMatchIn(title.trim())) return false
        } else if (!isPayApp(context, pkg) && !bankish.containsMatchIn(pkg)) {
            return false
        }
        val text = paymentNotificationBody(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val all = PayScreenParser.normalize("$title $text")
        // A real payment always has an amount; without one it's more likely a bank ad or news
        val sum = PayScreenParser.amount(all)
        if (sum == null) {
            PayDebug.add(tr("Без суммы — не оплата", "No amount — not a payment"), pkg, all)
            return false
        }
        // Wallets (Google Wallet, Samsung Wallet, Mir Pay) write only the shop, the amount and the card, without the word "payment";
        // an amount right after the phone payment screen, from the same app (payment app or bank SMS), is that payment
        val now = SystemClock.elapsedRealtime()
        val recent = PaymentRules.followsReady(sbn.postTime, readyWallAt, now, readyShownAt, PAY_WINDOW_MS) &&
            (pkg == readyShownPkg || sms || readyShownPkg == appFor(GMS) && pkg in wallets)
        val paid = !skip.containsMatchIn(all) && (!transfer.containsMatchIn(all) || recent) &&
            (success.containsMatchIn(all) || pkg in wallets || recent)
        PayDebug.add(if (paid) trf("Оплачено {0}", "Paid {0}", sum) else tr("Не похоже на оплату", "Doesn't look like a payment"), pkg, all)
        if (!paid) return false
        val event = "notification:$pkg:${sbn.key}:${sbn.postTime}"
        if (payments.duplicate(sum, event, if (recent) paymentSession else 0L, now)) return true
        showPaid(context, sum, if (sms) title else NotificationParser.appLabel(context, pkg), pkg)
        return true
    }

    private var smsPkg: String? = null
    private var smsCheckedAt = 0L

    /** The default SMS app, checked once a minute since it rarely changes. */
    private fun defaultSms(context: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (smsCheckedAt == 0L || now - smsCheckedAt > 60_000) {
            smsCheckedAt = now
            smsPkg = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        }
        return smsPkg
    }
}

/**
 * "Payment diagnostics": what the island saw (payment screen, bank and wallet notifications, bank SMS)
 * and how it interpreted it. The last 15 entries, in memory only: empty after a restart, never sent anywhere.
 */
object PayDebug {
    val log = MutableStateFlow<List<String>>(emptyList())

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private var last = ""

    fun add(result: String, pkg: String, text: String = "") {
        // The payment screen is parsed many times in a row, so we record the same thing once
        val what = buildString {
            append(result).append('\n').append(pkg)
            if (text.isNotBlank()) append('\n').append(com.akira.island.security.redactDiagnosticNumbers(text).trim().take(200))
        }
        if (what == last) return
        last = what
        log.value = (listOf("${clock.format(Date())} · $what") + log.value).take(15)
    }
}
