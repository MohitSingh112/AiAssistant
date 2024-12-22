package com.example.myapplication

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.AlarmClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CODE_CALL_PERMISSION = 1001
        private const val SUGGESTION_DELAY = 500L // Delay in milliseconds
    }
    // Variable to store the phone number temporarily
    private var pendingCallNumber: String? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var suggestionJob: Job? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()


    // Define UI components as class properties
    private lateinit var etQuestion: EditText
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var btnSubmit: Button
    private lateinit var btnScheduleTask: Button
    private lateinit var suggestionsChipGroup: ChipGroup
    private lateinit var progressBar: CardView
    private lateinit var defaultImage : LinearLayout


    //private val client = OkHttpClient()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etQuestion  = findViewById(R.id.queryInput)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        btnSubmit = findViewById(R.id.submitButton)
        btnScheduleTask = findViewById(R.id.scheduleTaskButton)
        suggestionsChipGroup = findViewById(R.id.suggestionsChipGroup)
        progressBar = findViewById(R.id.progressBarCard)
        defaultImage = findViewById(R.id.linerLayout)


        // Add text change listener with debounce
//        etQuestion.addTextChangedListener(object : TextWatcher {
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
//            override fun afterTextChanged(s: Editable?) {
//                suggestionJob?.cancel()
//                if (!s.isNullOrEmpty() && s.toString().trim().contains(" ")) {
//                    suggestionJob = scope.launch {
//                        delay(SUGGESTION_DELAY)
//                        getNextWordSuggestions(s.toString())
//                    }
//                } else {
//                    runOnUiThread {
//                        suggestionsChipGroup.removeAllViews()
//                    }
//                }
//            }
//        })

        setupRecyclerView()
        setupClickListeners()

    }

    private fun updateDefaultImage() {
        if (chatAdapter.itemCount == 0) {
            defaultImage.visibility = View.VISIBLE
        } else {
            defaultImage.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        btnScheduleTask.setOnClickListener {
            val task = etQuestion.text.toString()
            if (task.isNotBlank()) {
                showLoading()
                // Send a specifically formatted prompt
                val promptForAction = """
                Analyze this command: "$task"
                If it's about making a call, if the phone number is given then respond only with: CALL: <phone_number>
                If it's about sending an email, only if email address and subject of the email both are in the command then respond only with: EMAIL: <recipient>|<subject>|<generated_content>
                If it's about setting an alarm, if time is given then respond only with: ALARM: <time in HH:mm format>
                If it's about opening app , respond only with: APP: <app_name>
                If it's about anything but you not sure about it then respond only with: INCOMPLETE: <asking for question about the command>
                Note* - don't respond anything expect what is told 
                      - generate content for email based on the information user provided
                      - don't add <> in the response
                """.trimIndent()
                getResponse(promptForAction) { response ->
                    runOnUiThread {
                        hideLoading()
                        parseAndExecuteAction(response)
                    }
                }
            }
        }

        btnSubmit.setOnClickListener {
            val question = etQuestion.text.toString()
            if (question.isNotBlank()) {
                showLoading()
                // Add user message to chat
                addMessage(ChatMessage(question, "user"))
                scrollToBottom()

                getResponse(question) { response ->
                    runOnUiThread {
                        hideLoading()
                        // Add AI response to chat
                        addMessage(ChatMessage(response, "model"))
                        scrollToBottom()
                        clearInput()
                    }
                }
            }

        }
    }

    private fun addMessage(message: ChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        updateDefaultImage()
    }

    private fun getResponse(question: String, callback: (String) -> Unit){
        val model = GenerativeModel(
            modelName = "gemini-1.5-flash-001",
            apiKey =  BuildConfig.API_KEY,
            generationConfig = generationConfig {
                temperature = 0.15f
                topK = 32
                topP = 1f
                maxOutputTokens = 4096
            },
            safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE),
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.MEDIUM_AND_ABOVE),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.MEDIUM_AND_ABOVE),
            )
        )
        val chat = model.startChat(
            history = messages.map {
                content (it.role){text(it.text)}
            }.toList()
        )

        scope.launch {
            try {
                val response = chat.sendMessage(question)
                val text = response.text ?: "No response generated"
                Log.v("data", text)
                callback(text)
            } catch (e: Exception) {
                callback("Error: ${e.message}")
            }
        }
    }

    // to show loading (ui element)
    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        btnSubmit.isEnabled = false
        btnScheduleTask.isEnabled = false
        etQuestion.isEnabled = false
    }

    // to hide loading (ui element)
    private fun hideLoading() {
        progressBar.visibility = View.GONE
        btnSubmit.isEnabled = true
        btnScheduleTask.isEnabled = true
        etQuestion.isEnabled = true
    }

//    private fun getResponse(question: String, callback: (String) -> Unit){
//
//        val apiKey = ""
//        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
//
//        val requestBody =  """{"messages":[{"role":"user","content":"$question"}],"web_access":false}""".trimIndent()
//
//        val request = Request.Builder()
//            .url(url)
//            .get()
//            .addHeader("x-rapidapi-key", apiKey)
//            .addHeader("x-rapidapi-host", "chatgpt-42.p.rapidapi.com")
//            .addHeader("Content-Type", "application/json")
//            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
//            .build()
//
//        client.newCall(request).enqueue(object : Callback {
//            override fun onFailure(call: Call, e: IOException) {
//                Log.e("error","API failed",e)
//            }
//
//            override fun onResponse(call: Call, response: Response) {
//                // Ensure the body is read as a string
//
//                val body = response.body?.string()
//
//                if (body != null) {
//                    Log.v("data", body)
//
//                    try {
//                        val jsonObject = JSONObject(body)
//
//                        // Check if the "result" key exists before accessing it
//                        if (jsonObject.has("result")) {
//                            val textResult = jsonObject.getString("result")
//                            callback(textResult)
//                        } else {
//                            Log.v("data", "Key 'result' not found in the response")
//                        }
//
//                    } catch (e: JSONException) {
//                        Log.e("JSON Error", "Error parsing JSON: ${e.message}")
//                    }
//
//                } else {
//                    Log.v("data", "empty")
//                }
//            }
//        })
//    }

    private fun parseAndExecuteAction(apiResponse: String) {
        try {
            val result = apiResponse.trim()

            // Check if the result starts with a known command
            when {
                result.startsWith("CALL:") -> {
                    val number = result.substringAfter("CALL:").trim()
                    initiateCall(number)
                }
                result.startsWith("EMAIL:") -> {
                    val parts = result.substringAfter("EMAIL:").trim().split("|")
                    if (parts.size == 3) {
                        sendEmail(parts[0].trim(), parts[1].trim(), parts[2].trim())
                    }
                }
                result.startsWith("ALARM:") -> {
                    val timeStr = result.substringAfter("ALARM:").trim()
                    setAlarm(this,timeStr)
                }
                result.startsWith("APP:") -> {
                    val appName = result.substringAfter("APP:").trim()
                    openAppByName(appName)
                }
                result.startsWith("INCOMPLETE:") -> {
                    val response = result.substringAfter("INCOMPLETE:").trim()
                    addMessage(ChatMessage(response, "model"))
                }
                else -> {
                    Toast.makeText(this, "Couldn't understand the command", Toast.LENGTH_SHORT).show()
                }

            }
        } catch (e: Exception) {
            Log.e("error:", e.toString())
            Toast.makeText(this, "Error processing command: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    private fun initiateCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            // Save the number before requesting permission
            pendingCallNumber = number
            Log.v("number:", number)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CALL_PHONE),
                REQUEST_CODE_CALL_PERMISSION
            )
            return
        }

        // Make the call if permission is already granted
        makePhoneCall(number)
    }

    private fun makePhoneCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${number.replace(Regex("[^0-9+]"), "")}")
                Log.v("number2:", data.toString())
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to make call: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_CALL_PERMISSION -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission was granted, make the call using the saved number
                    pendingCallNumber?.let { number ->
                        makePhoneCall(number)
                    }
                } else {
                    Toast.makeText(this, "Call permission denied", Toast.LENGTH_SHORT).show()
                }
                // Clear the pending number regardless of the result
                pendingCallNumber = null
            }
        }
    }

    private fun sendEmail(recipient: String, subject: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        try {
            startActivity(Intent.createChooser(intent, "Send email using..."))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No email clients installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAlarm(context: Context, timeString: String) {
        // Parse the time string
        val (hours, minutes) = when {
            timeString.contains(":") -> timeString.split(":").map { it.toInt() }
            timeString.length == 4 -> listOf(
                timeString.substring(0, 2).toInt(),
                timeString.substring(2).toInt()
            )
            else -> throw IllegalArgumentException("Invalid time format")
        }

        // Create intent to open system alarm app
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmClock.EXTRA_HOUR, hours)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_MESSAGE, "My Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false) // Set to true if you want to skip the alarm app UI
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this,"no alarm application available",Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun openAppByName(appName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(appName)
                ?: packageManager.getLaunchIntentForPackage(
                    packageManager.getInstalledApplications(0)
                        .find { it.loadLabel(packageManager).toString().equals(appName, ignoreCase = true) }
                        ?.packageName ?: throw Exception("App not found")
                )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't find or open the app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getNextWordSuggestions(currentText: String) {
        val prompt = """
            Given this incomplete text: "$currentText"
            Suggest 3 likely next words that would naturally continue this text.
            Respond with ONLY the 3 words separated by commas, nothing else.
            For example: "word1,word2,word3"
        """.trimIndent()

        getResponse(prompt) { response ->
            val suggestions = response.split(",").map { it.trim() }.take(3)
            runOnUiThread {
                updateSuggestionChips(suggestions, etQuestion)
            }
        }
    }

    private fun updateSuggestionChips(suggestions: List<String>, editText: EditText) {
        suggestionsChipGroup.removeAllViews()

        suggestions.forEach { suggestion ->
            val chip = Chip(this).apply {
                text = suggestion
                isCheckable = false
                setOnClickListener {
                    val currentText = editText.text.toString()
                    val lastSpace = currentText.lastIndexOf(" ")
                    val newText = if (lastSpace >= 0) {
                        currentText.substring(0, lastSpace + 1) + suggestion + " "
                    } else {
                        "$currentText $suggestion "
                    }
                    editText.setText(newText)
                    editText.setSelection(newText.length)
                }
            }
            suggestionsChipGroup.addView(chip)
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            updateDefaultImage()
        }
    }

    private fun clearInput() {
        etQuestion.text.clear()
        suggestionsChipGroup.removeAllViews()
    }

    private fun scrollToBottom() {
        chatRecyclerView.post {
            chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }


}