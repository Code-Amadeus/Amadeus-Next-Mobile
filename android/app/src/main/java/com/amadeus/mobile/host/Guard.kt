package com.amadeus.mobile.host

/**
 * 唯一闸门的判决规则（设计文档第七节）。纯逻辑、不依赖 Android，规则随 APK 发布，控制面无法修改。
 * 判决只看执行面自己读到的屏幕事实，不采信模型给的任何描述。
 */
object Guard {
    sealed class Decision {
        object Allow : Decision()
        data class Confirm(val prompt: String) : Decision()
        data class Block(val code: String, val reason: String) : Decision()
    }

    /** 执行面观察到的屏幕事实。 */
    data class Screen(
        val packageName: String,
        val activity: String,
        val locked: Boolean,
        val hasPassword: Boolean,
        val texts: List<String>,
        val appLabel: String = packageName,
    )

    const val SELF = "com.amadeus.mobile"

    /** 支付与银行类应用：不能启动、不能读、不能操作。 */
    val PAYMENT_PACKAGES = setOf(
        "com.eg.android.AlipayGphone", "com.unionpay", "com.chinamworld.main", "com.icbc", "cmb.pb",
        "com.chinamworld.bocmbci", "com.android.bankabc", "com.bankcomm.Bankcomm", "com.ecitic.bank.mobile",
        "com.cib.cibmb", "com.spdb.mobilebank.per", "com.pingan.paces.ccms", "com.webank.wemoney",
        "com.mybank.android.phone", "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
    )

    /** 超级应用内的收银台：按页面文字识别。 */
    private val CASHIER_TEXTS = listOf("收银台", "确认付款", "输入支付密码", "请输入支付密码", "支付密码", "免密支付", "指纹支付", "刷脸支付", "确认支付")

    /** 系统安全相关界面：可以看，不能动——这些决定只属于主人。 */
    private val PROTECTED_PACKAGES = setOf(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
        "com.samsung.accessibility", "com.android.vending",
    )
    private val PROTECTED_SETTINGS = listOf("Accessibility", "DeviceAdmin", "InstalledAppDetails", "ManageApplications", "UsageAccess", "NotificationAccess", "Security", "Biometric", "Password", "Lock")

    private val CONFIRM_WORDS = listOf(
        "发送", "提交", "下单", "购买", "付款", "支付", "转账", "删除", "清空", "卸载", "退出登录", "注销", "授权",
        "关注", "发布", "拨打", "呼叫", "确认订单", "立即购买", "立即支付",
    )
    // 英文按整词匹配，避免 "display" 命中 pay、"recall" 命中 call
    private val CONFIRM_ENGLISH = Regex("\\b(send|submit|buy|pay|delete|post|publish|uninstall|call)\\b", RegexOption.IGNORE_CASE)
    private val MESSAGING_PACKAGES = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "org.telegram.messenger", "com.whatsapp", "com.android.mms", "com.samsung.android.messaging", "com.google.android.apps.messaging")

    private val READ_VERBS = setOf("observe", "screenshot", "list_apps", "wait", "remind_list")
    private val ACTION_VERBS = setOf("click", "swipe", "set_value", "enter", "key")

    /** 敏感界面：连内容都不返回。 */
    fun sensitive(screen: Screen): String? = when {
        screen.locked -> null
        screen.packageName in PAYMENT_PACKAGES -> "payment or banking app"
        screen.hasPassword -> "a password field is on screen"
        screen.texts.any { text -> CASHIER_TEXTS.any { text.contains(it) } } -> "payment screen"
        else -> null
    }

    private fun protected(screen: Screen): String? = when {
        screen.packageName == SELF -> "Amadeus's own screens and confirmations are for the user only"
        screen.packageName in PROTECTED_PACKAGES -> "permission and security screens are for the user to decide"
        screen.packageName == "com.android.settings" && PROTECTED_SETTINGS.any { screen.activity.contains(it, ignoreCase = true) } ->
            "security-related settings are for the user to decide"
        else -> null
    }

    /**
     * @param target 动作落点上的文字（节点标签），坐标动作取覆盖该点的元素；无落点时为空
     */
    fun judge(verb: String, args: Map<String, Any?>, screen: Screen?, target: String?): Decision {
        if (verb == "launch_app") {
            val pkg = args["package"] as? String ?: return Decision.Block("bad_args", "package is required")
            if (pkg in PAYMENT_PACKAGES) return Decision.Block("bad_package", "payment and banking apps are off limits; ask the user to open it")
            if (pkg == SELF) return Decision.Block("bad_package", "you are already running inside Amadeus")
            return Decision.Allow
        }
        if (verb in READ_VERBS || verb.startsWith("camera_") || verb.startsWith("remind_")) {
            if (screen != null && (verb == "observe" || verb == "screenshot")) {
                sensitive(screen)?.let { return Decision.Block("guard_block", "sensitive screen ($it); ask the user to handle it") }
            }
            return Decision.Allow
        }
        if (verb !in ACTION_VERBS) return Decision.Block("unknown_verb", "unknown verb: $verb")
        if (screen == null) return Decision.Block("not_found", "observe the screen first")
        if (screen.locked) return Decision.Block("screen_locked", "the phone is locked; ask the user to unlock it")
        // 系统全局键不针对任何应用，敏感界面上也允许按返回/主页离开
        if (verb == "key") return Decision.Allow
        sensitive(screen)?.let { return Decision.Block("guard_block", "sensitive screen ($it); ask the user to handle it") }
        protected(screen)?.let { return Decision.Block("guard_block", it) }
        if (verb == "swipe" || verb == "set_value") return Decision.Allow

        val label = target.orEmpty()
        if (CONFIRM_WORDS.any { label.contains(it) } || CONFIRM_ENGLISH.containsMatchIn(label)) return Decision.Confirm("想点击「${label.take(30)}」（${screen.appLabel}）")
        if (verb == "enter" && screen.packageName in MESSAGING_PACKAGES) return Decision.Confirm("想在 ${screen.appLabel} 里发送输入框的内容")
        return Decision.Allow
    }
}
