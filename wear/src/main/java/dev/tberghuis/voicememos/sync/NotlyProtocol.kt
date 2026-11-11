package dev.tberghuis.voicememos.sync

/**
 * Watch <-> phone Wearable Data Layer protocol (v1).
 *
 * KEEP IN SYNC with the phone copy:
 * notly-mobile-app/android/app/src/main/kotlin/com/notly/app/watch/WatchProtocol.kt
 * and PROTOCOL.md at the root of this project.
 *
 * All payloads are UTF-8 JSON. Request/response calls use MessageClient.sendRequest (RPC).
 */
object NotlyProtocol {
  const val CAPABILITY_WATCH = "notly_wear"
  const val CAPABILITY_PHONE = "notly_phone"

  // phone -> watch (RPC)
  const val PATH_AUTHORIZE = "/notly/v1/authorize"
  const val PATH_REVOKE = "/notly/v1/revoke"
  const val PATH_STATUS = "/notly/v1/status"

  // watch -> phone (RPC)
  const val PATH_OFFER = "/notly/v1/offer"
  const val PATH_VERIFY = "/notly/v1/verify"

  // watch -> phone (ChannelClient), suffixed with the recordingId
  const val PATH_FILE_PREFIX = "/notly/v1/file/"

  // response "status" values
  const val STATUS_OK = "ok"
  const val STATUS_SEND = "send"
  const val STATUS_HAVE = "have"
  const val STATUS_PENDING = "pending"
  const val STATUS_MISSING = "missing"
  const val STATUS_CORRUPT = "corrupt"
  const val STATUS_UNAUTHORIZED = "unauthorized"
  const val STATUS_ERROR = "error"
}
