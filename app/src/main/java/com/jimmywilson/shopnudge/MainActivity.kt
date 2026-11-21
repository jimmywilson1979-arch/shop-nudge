package com.jimmywilson.shopnudge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_ADD = "com.jimmywilson.shopnudge.ADD"
        private val DEFAULT_CATS = setOf(Category.SUPERMARKET, Category.CORNER_SHOP)
    }

    private lateinit var root: View
    private lateinit var input: TextInputEditText
    private lateinit var brandInput: TextInputEditText
    private lateinit var catGroup: ChipGroup
    private lateinit var itemsBox: LinearLayout
    private lateinit var listHeader: TextView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private lateinit var setupCard: View
    private lateinit var setupText: TextView
    private lateinit var setupBtn: MaterialButton
    private lateinit var refreshBtn: MaterialButton

    private var askedBackground = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermsChanged() }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                input.setText(text.replaceFirstChar { it.uppercase() })
                input.setSelection(input.text?.length ?: 0)
            }
        }

    private val photoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) readScreenshot(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        input = findViewById(R.id.itemInput)
        brandInput = findViewById(R.id.brandInput)
        catGroup = findViewById(R.id.catGroup)
        itemsBox = findViewById(R.id.itemsBox)
        listHeader = findViewById(R.id.listHeader)
        emptyText = findViewById(R.id.emptyText)
        statusText = findViewById(R.id.statusText)
        setupCard = findViewById(R.id.setupCard)
        setupText = findViewById(R.id.setupText)
        setupBtn = findViewById(R.id.setupBtn)
        refreshBtn = findViewById(R.id.refreshBtn)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        Category.entries.forEach { c ->
            val chip = layoutInflater.inflate(R.layout.chip_filter, catGroup, false) as Chip
            chip.text = "${c.emoji} ${c.label}"
            chip.tag = c
            chip.isChecked = c in DEFAULT_CATS
            catGroup.addView(chip)
        }

        findViewById<MaterialButton>(R.id.addBtn).setOnClickListener { addItem() }
        findViewById<MaterialButton>(R.id.micBtn).setOnClickListener { startVoice() }
        findViewById<MaterialButton>(R.id.photoBtn).setOnClickListener {
            photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.shopsBtn).setOnClickListener { showShops() }
        findViewById<MaterialButton>(R.id.testBtn).setOnClickListener { testAlert() }
        refreshBtn.setOnClickListener {
            if (!GeofenceManager.hasForeground(this)) {
                toast("Tap Set up first so I can see where you are")
            } else {
                RefreshWorker.enqueue(this)
            }
        }
        setupBtn.setOnClickListener { nextSetupStep() }
        findViewById<MaterialButton>(R.id.quietBtn).setOnClickListener { addQuietHere() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { addItem(); true } else false
        }

        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(RefreshWorker.UNIQUE).observe(this) { infos ->
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            refreshBtn.isEnabled = !running
            refreshBtn.text = if (running) "Searching…" else "Refresh shops"
            renderStatus()
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateSetup()
        renderList()
        renderStatus()
        renderQuiet()
    }

    // ---------------------------------------------------------------- quiet places

    private fun renderQuiet() {
        val box = findViewById<LinearLayout>(R.id.quietBox)
        box.removeAllViews()
        val places = Store.quietPlaces(this)
        if (places.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "None yet. Add home so it stops nudging you there."
                alpha = 0.7f
                setPadding(0, 8, 0, 8)
            })
        }
        places.forEachIndexed { i, p ->
            box.addView(TextView(this).apply {
                text = "📍 ${p.name}"
                textSize = 16f
                setPadding(0, 18, 0, 18)
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Remove ${p.name}?")
                        .setMessage("You'll get nudges around here again.")
                        .setPositiveButton("Remove") { _, _ ->
                            Store.saveQuietPlaces(this@MainActivity, places.filterIndexed { j, _ -> j != i })
                            renderQuiet()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun addQuietHere() {
        if (!GeofenceManager.hasForeground(this)) {
            toast("Tap Set up first so I can see where you are")
            return
        }
        toast("Finding where you are…")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    toast("Couldn't get your location. Try again in a moment")
                    return@addOnSuccessListener
                }
                val name = TextInputEditText(this).apply {
                    setText(if (Store.quietPlaces(this@MainActivity).isEmpty()) "Home" else "Work")
                    selectAll()
                }
                val wrap = LinearLayout(this).apply { setPadding(60, 20, 60, 0); addView(name) }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Name this quiet place")
                    .setView(wrap)
                    .setPositiveButton("Save") { _, _ ->
                        val n = name.text?.toString()?.trim().orEmpty().ifEmpty { "Quiet place" }
                        Store.addQuietPlace(this, QuietPlace(n, loc.latitude, loc.longitude))
                        renderQuiet()
                        snack("No more nudges around $n")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener { toast("Couldn't get your location. Try again in a moment") }
    }

    // ---------------------------------------------------------------- adding

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    if (uri != null) readScreenshot(uri) else toast("Couldn't open that picture")
                    return
                }
                val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                val cleaned = cleanShared(raw)
                if (cleaned.isNotEmpty()) input.setText(cleaned)
                focusInput()
            }
            ACTION_ADD -> focusInput()
        }
    }

    /** Turn shared text like "Robinsons Mini Squash https://..." into just the product name. */
    private fun cleanShared(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noUrls = raw.replace(Regex("https?://\\S+"), " ")
        val line = noUrls.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line.trim(' ', '-', '|', ':', '·').take(80)
    }

    /** Read a screenshot on the phone and let James tap the product name. */
    private fun readScreenshot(uri: Uri) {
        toast("Reading the screenshot…")
        ScreenshotReader.read(this, uri,
            onResult = { options ->
                if (options.isEmpty()) {
                    toast("Couldn't spot a product name, so type it in")
                    focusInput()
                    return@read
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Which one is it?")
                    .setItems(options.toTypedArray()) { _, which ->
                        input.setText(options[which])
                        focusInput()
                    }
                    .setNegativeButton("I'll type it") { _, _ -> focusInput() }
                    .show()
            },
            onError = { msg -> toast(msg) }
        )
    }

    private fun focusInput() {
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        input.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you need?")
        try {
            speechLauncher.launch(i)
        } catch (_: ActivityNotFoundException) {
            toast("Voice typing isn't available — use the mic on your keyboard")
        }
    }

    private fun selectedCats(): Set<Category> =
        catGroup.children.filterIsInstance<Chip>().filter { it.isChecked }.map { it.tag as Category }.toSet()

    private fun addItem() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            input.error = "Type (or say) what you need"
            return
        }
        val brand = brandInput.text?.toString()?.trim().orEmpty()
        val cats = selectedCats()
        if (cats.isEmpty() && brand.isEmpty()) {
            toast("Pick at least one type of shop")
            return
        }
        val now = System.currentTimeMillis()
        Store.addItem(this, Item(now, text, cats, brand, now))

        input.setText("")
        brandInput.setText("")
        catGroup.children.filterIsInstance<Chip>().forEach { it.isChecked = (it.tag as Category) in DEFAULT_CATS }
        renderList()

        if (GeofenceManager.hasForeground(this)) {
            RefreshWorker.enqueue(this)
            snack("Added. I'll nudge you at the shop 👍")
        } else {
            snack("Added. Tap Set up so I can nudge you at the shop")
        }
    }

    // ---------------------------------------------------------------- list

    private fun renderList() {
        val items = Store.items(this).sortedByDescending { it.added }
        itemsBox.removeAllViews()
        listHeader.text = if (items.isEmpty()) "Your list" else "Your list (${items.size})"
        emptyText.isVisible = items.isEmpty()
        items.forEach { item ->
            val row = layoutInflater.inflate(R.layout.row_item, itemsBox, false)
            row.findViewById<TextView>(R.id.itemText).text = item.text
            row.findViewById<TextView>(R.id.itemWhere).text = item.whereText()
            val check = row.findViewById<MaterialCheckBox>(R.id.itemCheck)
            check.setOnCheckedChangeListener { _, checked -> if (checked) tickOff(item) }
            row.setOnClickListener { editItem(item) }
            itemsBox.addView(row)
        }
    }

    /** Tap an item to change its name, shop types or "only at" shop. */
    private fun editItem(item: Item) {
        val view = layoutInflater.inflate(R.layout.dialog_edit, null)
        val text = view.findViewById<TextInputEditText>(R.id.editText)
        val brand = view.findViewById<TextInputEditText>(R.id.editBrand)
        val cats = view.findViewById<ChipGroup>(R.id.editCats)
        text.setText(item.text)
        brand.setText(item.brand)
        Category.entries.forEach { c ->
            val chip = layoutInflater.inflate(R.layout.chip_filter, cats, false) as Chip
            chip.text = "${c.emoji} ${c.label}"
            chip.tag = c
            chip.isChecked = c in item.cats
            cats.addView(chip)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit item")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ -> tickOff(item, "Deleted") }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newText = text.text?.toString()?.trim().orEmpty()
                val newBrand = brand.text?.toString()?.trim().orEmpty()
                val newCats = cats.children.filterIsInstance<Chip>().filter { it.isChecked }.map { it.tag as Category }.toSet()
                when {
                    newText.isEmpty() -> text.error = "Can't be blank"
                    newCats.isEmpty() && newBrand.isEmpty() -> toast("Pick at least one type of shop")
                    else -> {
                        Store.addItem(this, item.copy(text = newText, cats = newCats, brand = newBrand))
                        renderList()
                        renderStatus()
                        if (GeofenceManager.hasForeground(this)) RefreshWorker.enqueue(this)
                        dialog.dismiss()
                        snack("Saved")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun tickOff(item: Item, verb: String = "Got") {
        Store.removeItems(this, listOf(item.id))
        renderList()
        renderStatus()
        GeofenceManager.registerFromCache(this)
        Snackbar.make(root, "$verb: ${item.text}", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                Store.addItem(this, item)
                renderList()
                renderStatus()
                GeofenceManager.registerFromCache(this)
            }.show()
    }

    // ---------------------------------------------------------------- status & tools

    private fun renderStatus() {
        val items = Store.items(this)
        val center = Store.center(this)
        val watched = GeofenceManager.watchedShops(this).size
        val last = Store.lastRefresh(this)
        val err = Store.lastError(this)
        statusText.text = buildString {
            when {
                items.isEmpty() -> append("Nothing on your list, so no alerts are set.")
                !GeofenceManager.hasForeground(this@MainActivity) -> append("Finish Set up to switch alerts on.")
                center == null -> append("Haven't searched for shops yet. Tap Refresh shops.")
                watched == 0 -> append("No matching shops found within ${ShopFinder.RADIUS_M / 1000} km. I'll look again as you move about.")
                else -> append("Watching $watched shop${if (watched == 1) "" else "s"} near you for your list.")
            }
            if (last > 0 && items.isNotEmpty()) {
                append("\nLast checked ")
                append(DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
            }
            if (!err.isNullOrBlank()) append("\n⚠️ $err")
        }
    }

    private fun showShops() {
        val center = Store.center(this)
        val shops = GeofenceManager.watchedShops(this)
        if (center == null || shops.isEmpty()) {
            toast(if (Store.items(this).isEmpty()) "Add something to your list first" else "No shops yet. Tap Refresh shops")
            return
        }
        val items = Store.items(this)
        val lines = shops.map { s ->
            val km = s.distanceTo(center.first, center.second) / 1000f
            val forWhat = items.filter { it.matches(s) }.joinToString(", ") { it.text }
            String.format(Locale.UK, "%s · %.1f km\n   for: %s", s.label, km, forWhat)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Shops I'm watching (${shops.size})")
            .setItems(lines.toTypedArray()) { _, which ->
                val s = shops[which]
                val uri = Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.label)})")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun testAlert() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            toast("Notifications are off. Tap Set up first")
            return
        }
        val items = Store.items(this).ifEmpty {
            listOf(Item(0, "Robinsons Mini squash (test)", DEFAULT_CATS, "", 0))
        }
        Alerts.show(this, "🛒 Test: at Tesco? Don't forget…", items, 999)
        toast("Test alert sent. Check your phone and watch")
    }

    // ---------------------------------------------------------------- setup / permissions

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notifOk() = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
    private fun batteryOk() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun updateSetup() {
        val steps = listOf(
            "Notifications" to notifOk(),
            "Location" to GeofenceManager.hasForeground(this),
            "Location: 'Allow all the time'" to GeofenceManager.hasBackground(this),
            "Physical activity (walking vs driving)" to Movement.hasPermission(this),
            "Battery: don't let Android put it to sleep" to batteryOk(),
        )
        val missing = steps.filter { !it.second }
        setupCard.isVisible = missing.isNotEmpty()
        setupText.text = steps.joinToString("\n") { (name, ok) -> (if (ok) "✅  " else "⬜  ") + name }
        setupBtn.text = if (missing.isEmpty()) "All set" else "Next: ${missing.first().first.substringBefore(":")}"
    }

    private fun nextSetupStep() {
        when {
            !notifOk() -> permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            !GeofenceManager.hasForeground(this) -> permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            !GeofenceManager.hasBackground(this) -> {
                if (askedBackground) {
                    openAppSettings("Tap Permissions › Location › Allow all the time")
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("One more location step")
                        .setMessage("So I can nudge you while your phone's in your pocket, choose \"Allow all the time\" on the next screen.\n\nYour location never leaves your phone. Android's own geofencing does the watching, so it's light on battery.")
                        .setPositiveButton("OK") { _, _ ->
                            askedBackground = true
                            if (Build.VERSION.SDK_INT >= 29) {
                                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                            }
                        }
                        .show()
                }
            }
            !Movement.hasPermission(this) -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Walking or driving?")
                    .setMessage("This lets me nudge you as you walk past a shop, but stay quiet when you drive past one.\n\nOn the next screen, tap Allow.")
                    .setPositiveButton("OK") { _, _ ->
                        if (Build.VERSION.SDK_INT >= 29) permLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                    }
                    .show()
            }
            !batteryOk() -> {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: ActivityNotFoundException) {
                    openAppSettings("Tap Battery › Unrestricted")
                }
            }
        }
    }

    private fun openAppSettings(hint: String) {
        toast(hint)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun onPermsChanged() {
        Movement.register(this)
        updateSetup()
        renderStatus()
        if (GeofenceManager.hasForeground(this) && Store.items(this).isNotEmpty()) RefreshWorker.enqueue(this)
    }

    // ---------------------------------------------------------------- helpers

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun snack(msg: String) = Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
}
