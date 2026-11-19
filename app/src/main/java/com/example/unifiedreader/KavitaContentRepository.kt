package com.example.unifiedreader

import android.content.Context
import com.example.unifiedreader.core.model.AppReadableFormats
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.PartialDownloadFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class KavitaContentRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val kavitaApiFactory: KavitaApiFactory,
    private val kavitaCredentialStore: KavitaCredentialStore,
    private val cacheInvalidationDispatcher: DownloadedRemoteComicCacheInvalidationDispatcher,
) : RemoteDocumentDownloadRepository<KavitaStoredSession>(
    context = context,
    sourceType = SourceType.KAVITA,
    storageDirectoryName = "kavita-documents",
    supportedFormats = AppReadableFormats,
    sessionStore = kavitaCredentialStore,
    missingSessionMessage = "Connect to Kavita before downloading remote comics.",
) {
    private val sessionRequestRunner = KavitaStoredSessionRequestRunner(kavitaCredentialStore)

    override suspend fun download(
        session: KavitaStoredSession,
        item: ReadableItem,
        remoteItemId: String,
        target: PartialDownloadFile,
    ) {
        runWithStoredSessionAddressFailover(
            sessionStore = kavitaCredentialStore,
            session = session,
            createApi = kavitaApiFactory::create,
        ) { api, activeSession ->
            sessionRequestRunner.run(
                api = api,
                session = activeSession,
            ) { accessToken, apiKey ->
                api.downloadChapterResumably(
                    accessToken = accessToken,
                    chapterId = remoteItemId,
                    apiKey = apiKey,
                    target = target,
                )
            }
        }
    }

    override fun onStoredDocumentChanged(
        item: ReadableItem,
    ) {
        cacheInvalidationDispatcher.invalidate(item.id)
    }
}
