package dev.omalauncher.hunch

import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.*
import org.json.JSONObject

/** Account access only. No wallet creation, signing, deposit, order or withdrawal API. */
class AccountActivity:PaperActivity(){
    private val scope=MainScope()
    private lateinit var access:AccountAccess
    private var account:Account?=null
    private var email="";private var sent=false;private var busy=false;private var message="";private var demo=false;private var ready=false
    private data class Retained(val access:AccountAccess,val email:String,val sent:Boolean)
    override fun onRetainNonConfigurationInstance():Any=Retained(access,email,sent)
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        demo=BuildConfig.DEBUG&&intent.getBooleanExtra("demo",false)
        if(!demo)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val retained=lastNonConfigurationInstance as? Retained
        access=retained?.access ?: if(demo)DemoAccount() else LiveAccount(applicationContext)
        email=retained?.email.orEmpty();sent=retained?.sent ?: false
        openPaper()
    }
    private fun render(){if(ready)tell(JSONObject().put("type","auth").put("demo",demo).put("busy",busy).put("message",message).put("sent",sent).put("signed",account!=null).put("email",account?.email?:email).put("passkey",account?.passkey?:false))}
    override fun command(data:JSONObject){
        when(data.optString("type")){
            "ready"->{ready=true;checkAccount()}
            "close"->finish()
            "authAction"->{if(busy)return
                when(data.optString("action")){
                    "send"->{val entered=data.optString("email").trim();if(entered.length>254||!android.util.Patterns.EMAIL_ADDRESS.matcher(entered).matches()){email=entered.take(254);message="Enter a valid email address.";render()}else{email=entered;run("Sending code…","Could not send a code. Check the email and connection, then try again."){access.send(email);sent=true;message="Code sent. Check your email."}}}
                    "verify"->{if(!sent||account!=null)return;val code=data.optString("code");if(!Regex("[0-9]{6}").matches(code)){message="Enter the six-digit email code.";render()}else run("Verifying code…","That code could not be verified. Check it or request a new code."){account=access.verify(email,code);sent=false;message="Signed in. You can now secure your sign-in."}}
                    "resend"->if(sent&&account==null)run("Sending code…","Could not resend the code. Try again later."){access.send(email);message="A new code was sent."}
                    "different"->{if(account==null){sent=false;message="";render()}}
                    "login"->if(account==null)run("Waiting for your passkey…","Passkey sign-in did not complete. You can try again or use email."){account=access.login(this);message="Signed in."}
                    "link"->if(account?.email!=null&&account?.passkey==false)run("Waiting for you to create a passkey…","Passkey was not added. Your email login is still available."){account=access.addPasskey(this);message=if(account?.passkey==true)"Passkey added. Email recovery is still available." else "Passkey status has not updated. Check sign-in again."}
                    "logout"->if(account!=null)run("Signing out…","Could not sign out. Try again."){access.logout();account=null;email="";sent=false;message="Signed out."}
                    "check"->checkAccount()
                }
            }
        }
    }
    private fun checkAccount(){run("Checking sign-in…","Could not verify sign-in. Check your connection and try again."){account=access.current();message=""}}
    private fun run(wait:String,failure:String,operation:suspend ()->Unit){
        if(busy)return
        busy=true;message=wait;render()
        scope.launch{
            try{withTimeout(45_000){operation()}}
            catch(e:TimeoutCancellationException){message="This took too long. Check sign-in before trying again."}
            catch(e:CancellationException){throw e}
            catch(e:Exception){message=failure}
            finally{busy=false;if(!isFinishing&&!isDestroyed)render()}
        }
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
