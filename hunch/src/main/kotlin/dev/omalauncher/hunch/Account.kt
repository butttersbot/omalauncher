package dev.omalauncher.hunch

import android.app.Activity
import android.content.Context
import io.privy.auth.AuthState
import io.privy.auth.LinkedAccount
import io.privy.auth.PrivyUser
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig

internal data class Account(val email:String?,val passkey:Boolean)
internal interface AccountAccess {
    suspend fun current():Account?
    suspend fun send(email:String)
    suspend fun verify(email:String,code:String):Account
    suspend fun login(activity:Activity):Account
    suspend fun addPasskey(activity:Activity):Account
    suspend fun logout()
}
internal object PrivySession {
    private var instance:Privy?=null
    // Called on the UI thread; the SDK alone owns persisted credentials. No app token logging.
    fun get(context:Context):Privy = instance ?: Privy.init(context.applicationContext,
        PrivyConfig(BuildConfig.PRIVY_APP_ID,BuildConfig.PRIVY_CLIENT_ID,PrivyLogLevel.NONE,
            disableAutomaticMigration=true)).also{instance=it}
}
internal class LiveAccount(context:Context):AccountAccess {
    private val privy=PrivySession.get(context)
    private fun account(user:PrivyUser)=Account(
        user.linkedAccounts.filterIsInstance<LinkedAccount.EmailAccount>().firstOrNull()?.emailAddress,
        user.linkedAccounts.any{it is LinkedAccount.PasskeyAccount})
    override suspend fun current():Account? = when(val state=privy.getAuthState()) {
        is AuthState.Authenticated -> account(state.user)
        is AuthState.AuthenticatedUnverified -> error("Session is not verified")
        else -> null
    }
    override suspend fun send(email:String){privy.email.sendCode(email).getOrThrow()}
    override suspend fun verify(email:String,code:String)=account(privy.email.loginWithCode(code,email).getOrThrow())
    override suspend fun login(activity:Activity)=account(privy.passkey.login(BuildConfig.PRIVY_RP,activity).getOrThrow())
    override suspend fun addPasskey(activity:Activity):Account {
        check(current()?.email!=null){"Verified recovery email required"}
        return account(privy.passkey.link(BuildConfig.PRIVY_RP,"Hunch",activity).getOrThrow())
    }
    override suspend fun logout(){privy.logout()}
}
/** Only constructed behind the debug fixture gate. No SDK, no network, no device credentials. */
internal class DemoAccount:AccountAccess {
    private var value:Account?=null
    override suspend fun current()=value
    override suspend fun send(email:String){}
    override suspend fun verify(email:String,code:String):Account {
        check(code=="123456"){"Fictional code rejected"}
        return Account(email,false).also{value=it}
    }
    override suspend fun login(activity:Activity):Account=error("Fictional passkey cancelled")
    override suspend fun addPasskey(activity:Activity)=Account(value?.email,true).also{value=it}
    override suspend fun logout(){value=null}
}
