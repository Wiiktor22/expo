package expo.modules.kotlin.activityresult

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import expo.modules.core.utilities.ifNull
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.providers.CurrentActivityProvider
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * A registry that stores activity result callbacks ([ActivityResultCallback]) for
 * [AppContextActivityResultCaller.registerForActivityResult] registered calls.
 *
 * This class is created to address the problems of integrating original [ActivityResultRegistry]
 * with ReactNative and our current architecture.
 * There are two main problems:
 * - react-native-screen prevents us from using [Activity.onSaveInstanceState]/[Activity.onCreate] with `saveInstanceState`, because of https://github.com/software-mansion/react-native-screens/issues/17#issuecomment-424704067
 *   - this might be fixable in react-native-screens itself
 * - ReactNative does not provide any straightforward way to hook into every [Activity]/[Lifecycle] event that the original [ActivityResultRegistry] mechanism needs
 *   - there's room for further research in this topic
 *
 * Ideally we would get rid of this class in favour of the original one, but firstly we need to
 * solve these problems listed above.
 *
 * The implementation is based on [ActivityResultRegistry] coming from `androidx.activity:activity-ktx:1.4.0`.
 * Main differences are:
 * - it operates on two callbacks instead of one
 *   - fallback callback - the secondary callback that is registered at the very beginning of the registry lifecycle (at the very beginning of the app's lifecycle).
 *                         It is not aware of the context and serves to preserve the results coming from 3rd party Activity when Android kills the launching Activity.
 *                         Additionally there's a supporting field that is serialized and deserialized that might hold some additional info about the result (like further instructions what to do about the result)
 *   - main callback - regular callback that allows single path execution of the asynchronous 3rd party Activity calls
 * - it preserves the state across [Activity] recreation in different way - we use [android.content.SharedPreferences]
 * - it is adjusted to work with [AppContext] and the lifecycle events ReactNative provides.
 *
 * @see [ActivityResultRegistry] for more information.
 */
class AppContextActivityResultRegistry(
  private val currentActivityProvider: CurrentActivityProvider
) {
  private val LOG_TAG = "ActivityResultRegistry"

  // Use upper 16 bits for request codes
  private val INITIAL_REQUEST_CODE_VALUE = 0x00010000
  private var random: Random = Random()

  private val requestCodeToKey: MutableMap<Int, String> = HashMap()
  private val keyToRequestCode: MutableMap<String, Int> = HashMap()
  private val keyToLifecycleContainers: MutableMap<String, LifecycleContainer> = HashMap()
  private var launchedKeys: ArrayList<String> = ArrayList()

  /**
   * Registry storing both main callbacks and fallback callbacks and contracts associated with key.
   */
  private val keyToCallbacksAndContract: MutableMap<String, CallbacksAndContract<*, *>> = HashMap()

  /**
   * A register that stores contract-specific parameters that allow proper resumption of the process
   * in case of launching Activity being is destroyed.
   * These are serialized and deserialized.
   */
  private val keyToParamsForFallbackCallback: MutableMap<String, Any> = HashMap()

  private val pendingResults = Bundle/*<String, ActivityResult>*/()

  private val activity: AppCompatActivity
    get() = requireNotNull(currentActivityProvider.currentActivity) { "Current Activity is not available at the moment" }

  /**
   * @see [ActivityResultRegistry.onLaunch]
   * @see [ComponentActivity.mActivityResultRegistry] - this method code is adapted from this class
   */
  @MainThread
  fun <I, O> onLaunch(
    requestCode: Int,
    contract: ActivityResultContract<I, O>,
    @SuppressLint("UnknownNullness") input: I,
  ) {
    // Start activity path
    val intent = contract.createIntent(activity, input)
    var optionsBundle: Bundle? = null

    if (intent.hasExtra(StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE)) {
      optionsBundle = intent.getBundleExtra(StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE)
      intent.removeExtra(StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE)
    }
    when (intent.action) {
      RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS -> {
        // requestPermissions path
        var permissions = intent.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS)
        if (permissions == null) {
          permissions = arrayOfNulls(0)
        }
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
      }
      StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST -> {
        val request: IntentSenderRequest = intent.getParcelableExtra(StartIntentSenderForResult.EXTRA_INTENT_SENDER_REQUEST)!!
        try {
          // startIntentSenderForResult path
          ActivityCompat.startIntentSenderForResult(activity, request.intentSender,
            requestCode, request.fillInIntent, request.flagsMask,
            request.flagsValues, 0, optionsBundle)
        } catch (e: IntentSender.SendIntentException) {
          Handler(Looper.getMainLooper()).post {
            dispatchResult(requestCode, Activity.RESULT_CANCELED,
              Intent().setAction(StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST)
                .putExtra(StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION, e))
          }
        }
      }
      else -> {
        // startActivityForResult path
        ActivityCompat.startActivityForResult(activity, intent, requestCode, optionsBundle)
      }
    }
  }

  /**
   * This method should be called every time the Activity is created
   * @see [ActivityResultRegistry.register]
   * @param fallbackCallback callback that is invoked only if the Activity is destroyed and
   * recreated by the Android OS. Regular results are returned from [AppContextActivityResultLauncher.launch] method.
   */
  @MainThread
  fun <I, O, P: Bundleable<P>> register(
    key: String,
    lifecycleOwner: LifecycleOwner,
    contract: ActivityResultContract<I, O>,
    fallbackCallback: AppContextActivityResultFallbackCallback<O, P>
  ): AppContextActivityResultLauncher<I, O, P> {
    val lifecycle = lifecycleOwner.lifecycle

    keyToCallbacksAndContract[key] = CallbacksAndContract(fallbackCallback, null, contract)
    keyToRequestCode[key].ifNull {
      val requestCode = generateRandomNumber()
      requestCodeToKey[requestCode] = key
      keyToRequestCode[key] = requestCode
    }

    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> {
          // This is the most common path for returning results
          // When the Activity is destroyed then the other path is invoked, see [keyToFallbackCallback]

          // 1. No callbacks registered, other path would take care of the flow
          @Suppress("UNCHECKED_CAST")
          val callbacksAndContract: CallbacksAndContract<O, P> = (keyToCallbacksAndContract[key] ?: return@LifecycleEventObserver) as CallbacksAndContract<O, P>

          // 2. There are results to be delivered to the callbacks
          pendingResults.getParcelable<ActivityResult>(key)?.let {
            pendingResults.remove(key)

            val result = callbacksAndContract.contract.parseResult(it.resultCode, it.data)
            if (callbacksAndContract.mainCallback != null) {
              callbacksAndContract.mainCallback.onActivityResult(result)
            } else {
              @Suppress("UNCHECKED_CAST")
              val params = keyToParamsForFallbackCallback[key] as P
              callbacksAndContract.fallbackCallback.onActivityResult(result, params)
            }
          }
        }
        Lifecycle.Event.ON_DESTROY -> {
          unregister(key)
        }
        else -> Unit
      }
    }

    val lifecycleContainer = keyToLifecycleContainers[key] ?: LifecycleContainer(lifecycle)
    lifecycleContainer.addObserver(observer)
    keyToLifecycleContainers[key] = lifecycleContainer

    return object : AppContextActivityResultLauncher<I, O, P>() {
      override fun launch(input: I, params: P, callback: ActivityResultCallback<O>) {
        val requestCode = keyToRequestCode[key] ?: throw IllegalStateException("Attempting to launch an unregistered ActivityResultLauncher with contract $contract and input $input. You must ensure the ActivityResultLauncher is registered before calling launch()")
        launchedKeys.add(key)
        @Suppress("UNCHECKED_CAST")
        keyToCallbacksAndContract[key] = CallbacksAndContract(fallbackCallback, callback, contract)
        keyToParamsForFallbackCallback[key] = params as Any

        try {
          onLaunch(requestCode, contract, input)
        } catch (e: Exception) {
          launchedKeys.remove(key)
          throw e
        }
      }

      override val contract: ActivityResultContract<I, *> = contract
    }
  }

  /**
   * Persist the state of the registry.
   */
  fun persistInstanceState(context: Context) {
    DataPersistor(context)
      .addStringArrayList("launchedKeys", launchedKeys)
      .addStringToIntMap("keyToRequestCode", keyToRequestCode)
      .addStringToBundleableMap("keyToParamsForFallbackCallback", keyToParamsForFallbackCallback.filter { (key) -> launchedKeys.contains(key) })
      .addBundle("pendingResult", pendingResults)
      .addSerializable("random", random)
      .persist()
  }

  /**
   * Possibly restore saved results from before the registry was destroyed.
   */
  fun restoreInstanceState(context: Context) {
    val dataPersistor = DataPersistor(context)

    launchedKeys = dataPersistor.retrieveStringArrayList("launchedKeys")
    keyToParamsForFallbackCallback.putAll(dataPersistor.retrieveStringToBundleableMap("keyToParamsForFallbackCallback"))
    pendingResults.putAll(dataPersistor.retrieveBundle("pendingResult"))
    random = dataPersistor.retrieveSerializable("random") as Random

    val keyToRequestCode = dataPersistor.retrieveStringToIntMap("keyToRequestCode")
    keyToRequestCode.entries.forEach { (key, requestCode) ->
      this.keyToRequestCode[key] = requestCode
      this.requestCodeToKey[requestCode] = key
    }
  }

  /**
   * @see [ActivityResultRegistry.unregister]
   */
  @MainThread
  fun unregister(key: String) {
    if (!launchedKeys.contains(key)) {
      // Only remove the key -> requestCode mapping if there isn't a launch in flight
      keyToRequestCode.remove(key)?.let { requestCodeToKey.remove(it) }
    }
    keyToCallbacksAndContract.remove(key)
    if (pendingResults.containsKey(key)) {
      Log.w(LOG_TAG, "Dropping pending result for request $key : ${pendingResults.getParcelable<ActivityResult>(key)}")
      pendingResults.remove(key)
    }
    keyToLifecycleContainers[key]?.let {
      it.clearObservers()
      keyToLifecycleContainers.remove(key)
    }
  }

  /**
   * @see [ActivityResultRegistry.dispatchResult]
   */
  @MainThread
  fun dispatchResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    val key = requestCodeToKey[requestCode] ?: return false
    val callbacksAndContract = keyToCallbacksAndContract[key]
    doDispatch(key, resultCode, data, callbacksAndContract)
    return true
  }

  private fun <O, P: Bundleable<P>> doDispatch(key: String, resultCode: Int, data: Intent?, callbacksAndContract: CallbacksAndContract<O, P>?) {
    val currentLifecycleState = keyToLifecycleContainers[key]?.lifecycle?.currentState

    if (callbacksAndContract?.mainCallback != null && launchedKeys.contains(key)) {
      // 1. There's main callback available, so use it right away
      callbacksAndContract.mainCallback.onActivityResult(callbacksAndContract.contract.parseResult(resultCode, data))
      launchedKeys.remove(key)
    } else if (currentLifecycleState != null && currentLifecycleState.isAtLeast(Lifecycle.State.STARTED) && callbacksAndContract != null && launchedKeys.contains(key)) {
      // 2. Activity has already started, so let's proceed with fallback callback scenario
      @Suppress("UNCHECKED_CAST")
      val params = keyToParamsForFallbackCallback[key] as P
      callbacksAndContract.fallbackCallback.onActivityResult(callbacksAndContract.contract.parseResult(resultCode, data), params)
      launchedKeys.remove(key)
    } else {
      // 3. Add these pending results in their place in order to wait for Lifecycle-based path
      pendingResults.putParcelable(key, ActivityResult(resultCode, data))
    }
  }

  private fun generateRandomNumber(): Int {
    var number = (random.nextInt(Int.MAX_VALUE - INITIAL_REQUEST_CODE_VALUE + 1) + INITIAL_REQUEST_CODE_VALUE)
    while (requestCodeToKey.containsKey(number)) {
      number = (random.nextInt(Int.MAX_VALUE - INITIAL_REQUEST_CODE_VALUE + 1) + INITIAL_REQUEST_CODE_VALUE)
    }
    return number
  }

  private data class CallbacksAndContract<O, P: Bundleable<P>>(
    /**
     * Fallback callback that accepts both output and deserialized additional parameters
     */
    val fallbackCallback: AppContextActivityResultFallbackCallback<O, P>,
    /**
     * Main callback that might not be available, because the app might be re-created
     */
    val mainCallback: ActivityResultCallback<O>?,
    val contract: ActivityResultContract<*, O>,
  )

  class LifecycleContainer internal constructor(val lifecycle: Lifecycle) {
    private val observers: ArrayList<LifecycleEventObserver> = ArrayList()

    fun addObserver(observer: LifecycleEventObserver) {
      lifecycle.addObserver(observer)
      observers.add(observer)
    }

    fun clearObservers() {
      observers.forEach { lifecycle.removeObserver(it) }
      observers.clear()
    }
  }
}
