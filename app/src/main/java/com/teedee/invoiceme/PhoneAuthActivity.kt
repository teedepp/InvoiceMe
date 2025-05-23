package com.teedee.invoiceme

import android.content.Context // Needed for getSystemService(Context.INPUT_METHOD_SERVICE)
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils // Needed for formatNumberToE164
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Firebase
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import com.teedee.invoiceme.databinding.ActivityPhoneAuthBinding // Assuming this is your generated binding class
import java.util.concurrent.TimeUnit

class PhoneAuthActivity : AppCompatActivity() {

    // [START declare_view_binding]
    private lateinit var binding: ActivityPhoneAuthBinding
    // [END declare_view_binding]

    // [START declare_auth]
    private lateinit var auth: FirebaseAuth
    // [END declare_auth]

    private var storedVerificationId: String? = ""
    private lateinit var resendToken: PhoneAuthProvider.ForceResendingToken
    private lateinit var callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks

    // No longer need to declare individual UI elements with View Binding
    // private lateinit var phoneNumberEditText: EditText
    // private lateinit var sendCodeButton: Button
    // private lateinit var verificationCodeEditText: EditText
    // private lateinit var verifyCodeButton: Button
    // private lateinit var progressBar: ProgressBar
    // private lateinit var resendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [START initialize_view_binding]
        binding = ActivityPhoneAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // [END initialize_view_binding]

        // [START initialize_auth]
        auth = Firebase.auth
        // [END initialize_auth]

        // Initialize phone auth callbacks
        callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Log.d(TAG, "onVerificationCompleted:$credential")
                // Hide progress bar and show success message
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@PhoneAuthActivity, "Verification completed automatically.", Toast.LENGTH_SHORT).show()
                signInWithPhoneAuthCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.w(TAG, "onVerificationFailed", e)
                binding.progressBar.visibility = View.GONE

                val errorMessage: String = when (e) {
                    is FirebaseAuthInvalidCredentialsException -> "Invalid phone number or verification code."
                    is FirebaseTooManyRequestsException -> "Too many requests. Try again later."
                    is FirebaseAuthMissingActivityForRecaptchaException -> "reCAPTCHA verification failed. Please try again."
                    else -> "Verification failed: ${e.message}"
                }
                Toast.makeText(this@PhoneAuthActivity, errorMessage, Toast.LENGTH_LONG).show()
                binding.buttonResendCode.visibility = View.GONE // Hide resend button on failure
                resetUIForPhoneNumberInput() // Reset UI to initial state
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken,
            ) {
                Log.d(TAG, "onCodeSent:$verificationId")
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@PhoneAuthActivity, "Verification code sent!", Toast.LENGTH_SHORT).show()

                // Save verification ID and resending token
                storedVerificationId = verificationId
                resendToken = token

                // Update UI to show verification code input
                binding.editTextPhoneNumber.visibility = View.GONE
                binding.buttonSendCode.visibility = View.GONE
                binding.editTextVerificationCode.visibility = View.VISIBLE
                binding.buttonVerifyCode.visibility = View.VISIBLE
                binding.editTextVerificationCode.requestFocus() // Focus on the code input

                // [START show_resend_button_after_timeout]
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.buttonResendCode.visibility = View.VISIBLE
                    binding.buttonResendCode.isEnabled = true // Enable it after timeout
                }, 60000) // 60 seconds (adjust as per your timeout set in PhoneAuthOptions)
                // [END show_resend_button_after_timeout]
            }
        }

        // [START setup_click_listeners]
        binding.buttonSendCode.setOnClickListener {
            val phoneNumber = binding.editTextPhoneNumber.text.toString().trim()
            if (phoneNumber.isEmpty()) {
                binding.editTextPhoneNumber.error = "Phone number required"
                binding.editTextPhoneNumber.requestFocus()
                return@setOnClickListener
            }
            // Basic validation, adjust as needed, consider a more robust library for phone number validation
            if (phoneNumber.length < 10 || !phoneNumber.matches("^[0-9]+$".toRegex())) {
                binding.editTextPhoneNumber.error = "Enter a valid phone number"
                binding.editTextPhoneNumber.requestFocus()
                return@setOnClickListener
            }

            // [START format_phone_number_e164]
            // And determine the user's country code dynamically or default to a known one.
            // For this example, assuming "IN" for India.
            val countryCode = "IN" // TODO: Get this dynamically from user's locale or a country picker
            val formattedNumber = try {
                // PhoneNumberUtils.formatNumberToE164 might return null if the number is not valid for the country
                // or if it doesn't represent a real phone number in that context.
                PhoneNumberUtils.formatNumberToE164(phoneNumber, countryCode)
            } catch (e: Exception) {
                Log.e(TAG, "Error formatting phone number: $e")
                null
            }

            if (formattedNumber.isNullOrEmpty()) {
                binding.editTextPhoneNumber.error = "Invalid phone number for $countryCode"
                binding.editTextPhoneNumber.requestFocus()
                return@setOnClickListener
            }
            // [END format_phone_number_e164]

            // Show progress bar
            binding.progressBar.visibility = View.VISIBLE
            // Disable buttons to prevent multiple clicks
            hideKeyboard(it)
            binding.buttonSendCode.isEnabled = false
            startPhoneNumberVerification(formattedNumber) // Use the formatted number
        }

        binding.buttonVerifyCode.setOnClickListener {
            val code = binding.editTextVerificationCode.text.toString().trim()
            hideKeyboard(it)
            if (code.isEmpty()) {
                binding.editTextVerificationCode.error = "Verification code required"
                binding.editTextVerificationCode.requestFocus()
                return@setOnClickListener
            }
            if (code.length < 6) { // Most Firebase codes are 6 digits
                binding.editTextVerificationCode.error = "Enter a 6-digit code"
                binding.editTextVerificationCode.requestFocus()
                return@setOnClickListener
            }

            if (storedVerificationId != null) {
                // Show progress bar
                binding.progressBar.visibility = View.VISIBLE
                // Disable buttons
                binding.buttonVerifyCode.isEnabled = false
                verifyPhoneNumberWithCode(storedVerificationId, code)
            } else {
                Toast.makeText(this, "Verification ID missing. Please resend the code.", Toast.LENGTH_LONG).show()
                resetUIForPhoneNumberInput()
            }
        }

        binding.buttonResendCode.setOnClickListener {
            // Implement resend logic here
            // Make sure storedVerificationId and resendToken are available
            // Check if phone number is available as well, as resending needs it.
            if (!binding.editTextPhoneNumber.text.isNullOrEmpty() && storedVerificationId != null && this::resendToken.isInitialized) {
                binding.progressBar.visibility = View.VISIBLE
                binding.buttonResendCode.isEnabled = false // Disable to prevent multiple resends
                // IMPORTANT: Use the *formatted* phone number if available, or re-format
                val currentPhoneNumber = binding.editTextPhoneNumber.text.toString().trim()
                val countryCode = "IN" // Ensure this is consistent or dynamic
                val formattedNumber = PhoneNumberUtils.formatNumberToE164(currentPhoneNumber, countryCode)
                if (formattedNumber != null) {
                    resendVerificationCode(formattedNumber, resendToken)
                    binding.buttonResendCode.visibility = View.GONE // Hide it again immediately
                } else {
                    Toast.makeText(this, "Could not format phone number for resend.", Toast.LENGTH_SHORT).show()
                    resetUIForPhoneNumberInput()
                }

            } else {
                Toast.makeText(this, "Something went wrong, please try sending a new code.", Toast.LENGTH_SHORT).show()
                resetUIForPhoneNumberInput()
            }
        }
        // Initially hide the resend button
        binding.buttonResendCode.visibility = View.GONE
        // [END setup_click_listeners]
    }

    override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        updateUI(currentUser)
    }

    private fun startPhoneNumberVerification(phoneNumber: String) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifyPhoneNumberWithCode(verificationId: String?, code: String) {
        val credential = PhoneAuthProvider.getCredential(verificationId!!, code)
        signInWithPhoneAuthCredential(credential)
    }

    private fun resendVerificationCode(
        phoneNumber: String,
        token: PhoneAuthProvider.ForceResendingToken?,
    ) {
        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
        if (token != null) {
            optionsBuilder.setForceResendingToken(token)
        }
        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    private fun signInWithPhoneAuthCredential(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                binding.progressBar.visibility = View.GONE // Hide progress bar on completion
                binding.buttonVerifyCode.isEnabled = true
                if (task.isSuccessful) {
                    Log.d(TAG, "signInWithCredential:success")
                    val user = task.result?.user
                    Toast.makeText(this, "Signed In Successfully!", Toast.LENGTH_SHORT).show()
                    // [START navigate_to_main_app]
                    val intent = Intent(this, CreateInvoiceActivity::class.java) // Ensure YourMainActivity exists
                    startActivity(intent)
                    finish() // Prevent going back to login screen with back button
                    // [END navigate_to_main_app]
                    updateUI(user) // Update UI based on signed-in user
                } else {
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    if (task.exception is FirebaseAuthInvalidCredentialsException) {
                        Toast.makeText(this, "Invalid verification code.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Authentication failed. ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                    binding.buttonResendCode.visibility = View.GONE // Hide resend button if sign-in fails
                    resetUIForPhoneNumberInput() // Reset UI if sign-in fails
                    // Re-enable verification code input and button to allow retry
                    binding.editTextVerificationCode.isEnabled = true
                    binding.buttonVerifyCode.isEnabled = true
                }
            }
    }

    // This function will be called to update the UI based on user login state
    private fun updateUI(user: FirebaseUser? = auth.currentUser) {
        if (user != null) {
            // User is signed in
            binding.editTextPhoneNumber.visibility = View.GONE
            binding.buttonSendCode.visibility = View.GONE
            binding.editTextVerificationCode.visibility = View.GONE
            binding.buttonVerifyCode.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.buttonResendCode.visibility = View.GONE // Hide if user is signed in

            Toast.makeText(this, "User already signed in: ${user.phoneNumber}", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, CreateInvoiceActivity::class.java) // Ensure YourMainActivity exists
            startActivity(intent)
            finish()
        } else {
            // No user is signed in, show initial phone number input UI
            binding.editTextPhoneNumber.visibility = View.VISIBLE
            binding.buttonSendCode.visibility = View.VISIBLE
            binding.editTextVerificationCode.visibility = View.GONE
            binding.buttonVerifyCode.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.buttonResendCode.visibility = View.GONE // Ensure hidden initially

            binding.editTextPhoneNumber.isEnabled = true
            binding.buttonSendCode.isEnabled = true
            binding.editTextVerificationCode.isEnabled = true
            binding.buttonVerifyCode.isEnabled = true
            binding.buttonResendCode.isEnabled = true // Re-enable for next cycle
        }
    }

    // Helper to reset UI to the initial phone number input state
    private fun resetUIForPhoneNumberInput() {
        binding.editTextPhoneNumber.visibility = View.VISIBLE
        binding.buttonSendCode.visibility = View.VISIBLE
        binding.editTextVerificationCode.visibility = View.GONE
        binding.buttonVerifyCode.visibility = View.GONE
        binding.progressBar.visibility = View.GONE // Ensure progress bar is hidden

        binding.editTextPhoneNumber.isEnabled = true
        binding.buttonSendCode.isEnabled = true
        // Clear any previous inputs
        binding.editTextPhoneNumber.text.clear()
        binding.editTextVerificationCode.text.clear()

        binding.buttonResendCode.visibility = View.GONE // Ensure it's hidden when resetting UI
        binding.buttonResendCode.isEnabled = true // Re-enable for next cycle
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onBackPressed() {
        if (binding.progressBar.visibility == View.VISIBLE) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "PhoneAuthActivity"
    }
}