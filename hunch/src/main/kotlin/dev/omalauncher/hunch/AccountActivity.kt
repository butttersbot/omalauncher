package dev.omalauncher.hunch

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*

/** Account access only. No wallet creation, signing, deposit, order or withdrawal API. */
class AccountActivity:Activity() {
    private val scope=MainScope()
    private lateinit var access:AccountAccess
    private lateinit var page:LinearLayout
    private lateinit var body:Typeface
    private lateinit var headline:Typeface
    private val paper=Color.rgb(253,253,251);private val ink=Color.rgb(17,17,19)
    private var account:Account?=null;private var email="";private var sent=false
    private var busy=false;private var message="";private var demo=false
    private data class Retained(val access:AccountAccess,val email:String,val sent:Boolean)
    override fun onRetainNonConfigurationInstance():Any = Retained(access,email,sent)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        body=Typeface.createFromAsset(assets,"fonts/ZenKakuGothicNew-Regular.ttf")
        headline=Typeface.createFromAsset(assets,"fonts/DelaGothicOne-Regular.ttf")
        demo=BuildConfig.DEBUG&&intent.getBooleanExtra("demo",false)
        if(!demo)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Never persist the email/code fields or save a pending credential operation in a Bundle.
        val retained=lastNonConfigurationInstance as? Retained
        access=retained?.access ?: if(demo)DemoAccount() else LiveAccount(applicationContext)
        email=retained?.email.orEmpty();sent=retained?.sent ?: false
        run("Checking sign-in…","Could not verify sign-in. Check your connection and try again."){account=access.current()}
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(s:String,size:Float=16f)=TextView(this).apply{
        text=s;textSize=size;setTextColor(ink);typeface=body;setPadding(dp(12),dp(10),dp(12),dp(10))
    }
    private fun add(v:View){page.addView(v,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
    private fun button(s:String,action:()->Unit){add(Button(this).apply{text=s;typeface=body;isAllCaps=false;minHeight=dp(48);isEnabled=!busy;setOnClickListener{action()}})}
    private fun input(hintText:String,type:Int,value:String="")=EditText(this).apply{
        hint=hintText;contentDescription=hintText;inputType=type;setSingleLine();setText(value);typeface=body
        setTextColor(ink);setHintTextColor(Color.DKGRAY);isEnabled=!busy;isSaveEnabled=false
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        background=GradientDrawable().apply{setColor(paper);setStroke(dp(1),ink)}
        setPadding(dp(12),dp(12),dp(12),dp(12))
    }.also{add(it)}
    private fun render(){
        val root=ScrollView(this).apply{setBackgroundColor(paper);isFillViewport=true}
        root.setOnApplyWindowInsetsListener{v,i->v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(24))}
        root.addView(page);setContentView(root);root.requestApplyInsets()
        add(label(if(demo)"HUNCH / FICTIONAL SIGN-IN DEMO" else "HUNCH / YOUR ACCOUNT",12f))
        add(label("Sign in to Hunch",26f).apply{typeface=headline})
        add(label("Sign-in only. Markets remain read-only. No wallet or payment is created here.",14f))
        if(message.isNotEmpty())add(label(message,14f).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE})
        val user=account
        if(user==null){
            add(label("Start with email. Add a passkey after signing in; keep email for recovery.",15f))
            if(!sent){
                val field=input("Email address",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,email)
                button("Send email code"){
                    val entered=field.text.toString().trim()
                    if(!android.util.Patterns.EMAIL_ADDRESS.matcher(entered).matches()){message="Enter a valid email address.";email=entered;render()}
                    else {email=entered;run("Sending code…","Could not send a code. Check the email and connection, then try again."){access.send(email);sent=true;message="Code sent. Check your email."}}
                }
                button("Sign in with an existing passkey"){
                    run("Waiting for your passkey…","Passkey sign-in did not complete. You can try again or use email."){account=access.login(this);message="Signed in."}
                }
            }else{
                add(label("Code sent to $email",14f))
                val code=input("Email code",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                button("Verify email code"){
                    val value=code.text.toString().trim()
                    if(!Regex("[0-9]{6}").matches(value)){message="Enter the six-digit email code.";render()}
                    else run("Verifying code…","That code could not be verified. Check it or request a new code."){account=access.verify(email,value);sent=false;message="Signed in. You can now add a passkey."}
                }
                button("Resend email code"){run("Sending code…","Could not resend the code. Try again later."){access.send(email);message="A new code was sent."}}
                button("Use a different email"){sent=false;message="";render()}
            }
        }else{
            add(label("Signed in",22f))
            add(label(user.email?.let{"Recovery email: $it"} ?: "No recovery email is linked. Use your email login before adding a passkey.",15f))
            add(label(if(user.passkey)"Passkey added" else "Secure your sign-in",21f))
            add(label("Your email login stays available for recovery.",14f))
            if(!user.passkey&&user.email!=null)button("Add a passkey"){
                run("Waiting for you to create a passkey…","Passkey was not added. Your email login is still available."){account=access.addPasskey(this);message=if(account?.passkey==true)"Passkey added. Email recovery is still available." else "Passkey status has not updated. Check sign-in again."}
            }
            button("Sign out"){run("Signing out…","Could not sign out. Try again."){access.logout();account=null;email="";sent=false;message="Signed out."}}
        }
        button("Check sign-in again"){run("Checking sign-in…","Could not verify sign-in. Check your connection and try again."){account=access.current()}}
        button("Back to markets"){finish()}
    }
    private fun run(wait:String,failure:String,operation:suspend ()->Unit){
        if(busy)return
        busy=true;message=wait;render()
        scope.launch {
            try{withTimeout(45_000){operation()}}
            catch(e:TimeoutCancellationException){message="This took too long. Check sign-in before trying again."}
            catch(e:CancellationException){throw e}
            catch(e:Exception){message=failure} // Never expose raw SDK errors, tokens or OTPs.
            finally{busy=false;if(!isFinishing&&!isDestroyed)render()}
        }
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
