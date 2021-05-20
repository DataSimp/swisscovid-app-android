package ch.admin.bag.dp3t.inform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.liveData
import ch.admin.bag.dp3t.checkin.models.UploadVenueInfo
import ch.admin.bag.dp3t.checkin.models.UserUploadPayload
import ch.admin.bag.dp3t.checkin.networking.UserUploadRepository
import ch.admin.bag.dp3t.checkin.storage.DiaryStorage
import ch.admin.bag.dp3t.inform.models.Resource
import ch.admin.bag.dp3t.inform.models.SelectableCheckinItem
import ch.admin.bag.dp3t.networking.AuthCodeRepository
import ch.admin.bag.dp3t.networking.errors.InvalidCodeError
import ch.admin.bag.dp3t.networking.errors.ResponseError
import ch.admin.bag.dp3t.networking.models.AuthenticationCodeRequestModel
import ch.admin.bag.dp3t.storage.SecureStorage
import ch.admin.bag.dp3t.util.JwtUtil
import ch.admin.bag.dp3t.util.toUploadVenueInfo
import com.google.android.gms.common.api.ApiException
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import org.crowdnotifier.android.sdk.CrowdNotifier
import org.dpppt.android.sdk.PendingUploadTask
import org.dpppt.android.sdk.backend.ResponseCallback
import org.dpppt.android.sdk.models.DayDate
import org.dpppt.android.sdk.models.ExposeeAuthMethodAuthorization
import retrofit2.HttpException
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val USER_UPLOAD_VERSION = 3
private const val TIMEOUT_VALID_CODE = 1000L * 60 * 5
private const val MAX_EXPOSURE_AGE_MILLIS = 10 * 24 * 60 * 60 * 1000L
private const val ISOLATION_DURATION_DAYS = 14L
private const val KEY_COVIDCODE = "KEY_COVIDCODE"
private const val KEY_HAS_SHARED_DP3T_KEYS = "KEY_HAS_SHARED_DP3T_KEYS"
private const val KEY_HAS_SHARED_CHECKINS = "KEY_HAS_SHARED_CHECKINS"
private const val KEY_PENDING_UPLOAD_TASK = "KEY_PENDING_UPLOAD_TASK"
private const val USER_UPLOAD_SIZE = 1000

class InformViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {

	private val authCodeRepository = AuthCodeRepository(application)
	private val userUploadRepository = UserUploadRepository()
	private val diaryStorage = DiaryStorage.getInstance(application)
	private val secureStorage = SecureStorage.getInstance(application)
	private val random = Random()

	var selectableCheckinItems = diaryStorage.entries.map { SelectableCheckinItem(it, isSelected = false) }

	var covidCode: String
		get() = state.get<String>(KEY_COVIDCODE) ?: getLastCovidcode()
		set(value) = state.set(KEY_COVIDCODE, value)

	var hasSharedDP3TKeys: Boolean
		get() = state.get<Boolean>(KEY_HAS_SHARED_DP3T_KEYS) ?: false
		set(value) = state.set(KEY_HAS_SHARED_DP3T_KEYS, value)

	var hasSharedCheckins: Boolean
		get() = state.get<Boolean>(KEY_HAS_SHARED_CHECKINS) ?: false
		set(value) = state.set(KEY_HAS_SHARED_CHECKINS, value)

	var pendingUploadTask: PendingUploadTask?
		get() = state.get<PendingUploadTask?>(KEY_PENDING_UPLOAD_TASK)
		set(value) = state.set(KEY_PENDING_UPLOAD_TASK, value)

	fun performUpload() = liveData(Dispatchers.IO) {
		emit(Resource.loading(data = null))
		try {
			loadAccessTokens(covidCode)
		} catch (exception: Throwable) {
			when (exception) {
				is InvalidCodeError -> emit(Resource.error(InformRequestError.BLACK_INVALID_AUTH_RESPONSE_FORM, exception))
				is ResponseError -> emit(Resource.error(InformRequestError.BLACK_STATUS_ERROR, exception))
				else -> emit(Resource.error(InformRequestError.BLACK_MISC_NETWORK_ERROR, exception))
			}
			return@liveData
		}
		if (hasSharedDP3TKeys) {
			try {
				uploadTEKs()
			} catch (exception: Throwable) {
				when (exception) {
					is ResponseError -> emit(Resource.error(InformRequestError.RED_STATUS_ERROR, exception))
					is CancellationException -> emit(Resource.error(InformRequestError.RED_USER_CANCELLED_SHARE, exception))
					is ApiException -> emit(Resource.error(InformRequestError.RED_EXPOSURE_API_ERROR, exception))
					else -> emit(Resource.error(InformRequestError.RED_MISC_NETWORK_ERROR, exception))
				}
				return@liveData
			}
		}
		if (hasSharedCheckins) {
			try {
				val authorizationHeader = getAuthorizationHeader(secureStorage.lastCheckinInformToken)
				userUploadRepository.userUpload(getUserUploadPayload(), authorizationHeader)

			} catch (exception: Throwable) {
				when (exception) {
					is HttpException -> emit(Resource.error(InformRequestError.USER_UPLOAD_NETWORK_ERROR, exception))
					else -> emit(Resource.error(InformRequestError.USER_UPLOAD_UNKONWN_ERROR, exception))
				}
				return@liveData
			}
		}
		emit(Resource.success(data = null))
	}

	private fun getLastCovidcode(): String {
		val lastCovidcode = secureStorage.lastInformCode

		return if (System.currentTimeMillis() - secureStorage.lastInformRequestTime < TIMEOUT_VALID_CODE) {
			lastCovidcode ?: ""
		} else {
			""
		}
	}

	private suspend fun uploadTEKs() {
		val authorizationHeader = getAuthorizationHeader(secureStorage.lastDP3TInformToken)
		val onsetDate = JwtUtil.getOnsetDate(secureStorage.lastDP3TInformToken)

		var error: Throwable? = null

		// Wrapping traditional callback in a suspendCoroutine
		suspendCoroutine<Unit> { continuation ->
			pendingUploadTask?.performUpload(getApplication(), onsetDate, ExposeeAuthMethodAuthorization(authorizationHeader),
				object : ResponseCallback<DayDate> {
					override fun onSuccess(oldestSharedKeyDayDate: DayDate) {

						// Store the oldest shared Key date of this report (but at least now-MAX_EXPOSURE_AGE_MILLIS)
						val oldestSharedKeyDate = Math.max(
							oldestSharedKeyDayDate.startOfDayTimestamp,
							System.currentTimeMillis() - MAX_EXPOSURE_AGE_MILLIS
						)
						secureStorage.positiveReportOldestSharedKey = oldestSharedKeyDate

						// Ask if user wants to end isolation after 14 days
						val isolationEndDialogTimestamp =
							System.currentTimeMillis() + TimeUnit.DAYS.toMillis(ISOLATION_DURATION_DAYS)
						secureStorage.isolationEndDialogTimestamp = isolationEndDialogTimestamp
						hasSharedDP3TKeys = true
						continuation.resume(Unit)
					}

					override fun onError(throwable: Throwable) {
						error = throwable
						continuation.resume(Unit)
					}
				})
		}
		error?.let { throw(it) }
	}

	private suspend fun loadAccessTokens(covidcode: String) {
		val lastTimestamp = secureStorage.lastInformRequestTime
		val accessToken = secureStorage.lastDP3TInformToken
		val lastCovidcode = secureStorage.lastInformCode
		if (!(System.currentTimeMillis() - lastTimestamp < TIMEOUT_VALID_CODE && accessToken != null) || covidcode != lastCovidcode) {
			val accessTokens = authCodeRepository.getAccessToken(AuthenticationCodeRequestModel(covidcode, 0))
			secureStorage.saveInformTimeAndCodeAndToken(
				covidcode, accessTokens.dp3TAccessToken.accessToken, accessTokens.checkInAccessToken.accessToken
			)
		}
	}

	private fun getUserUploadPayload(): UserUploadPayload {
		val userUploadPayloadBuilder = UserUploadPayload.newBuilder().setVersion(USER_UPLOAD_VERSION)
		selectableCheckinItems.filter {
			it.isSelected
		}.map {
			CrowdNotifier.generateUserUploadInfo(it.diaryEntry.venueInfo, it.diaryEntry.arrivalTime, it.diaryEntry.departureTime)
		}.flatten().forEach {
			userUploadPayloadBuilder.addVenueInfos(it.toUploadVenueInfo())
		}

		for (i in userUploadPayloadBuilder.venueInfosCount until USER_UPLOAD_SIZE) {
			userUploadPayloadBuilder.addVenueInfos(getRandomFakeVenueInfo())
		}
		return userUploadPayloadBuilder.build()
	}

	private fun getRandomFakeVenueInfo(): UploadVenueInfo {
		return UploadVenueInfo.newBuilder()
			.setPreId(ByteString.copyFrom(getRandomByteArray(32)))
			.setTimeKey(ByteString.copyFrom(getRandomByteArray(32)))
			.setNotificationKey(ByteString.copyFrom(getRandomByteArray(32)))
			.setIntervalStartMs(random.nextLong())
			.setIntervalEndMs(random.nextLong())
			.setFake(true)
			.build()

	}

	private fun getRandomByteArray(size: Int): ByteArray {
		return ByteArray(size).apply {
			random.nextBytes(this)
		}
	}

	private fun getAuthorizationHeader(accessToken: String): String {
		return "Bearer $accessToken"
	}

}