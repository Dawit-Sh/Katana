package com.example.unifiedreader

import android.content.Context
import com.example.unifiedreader.core.model.ComicReaderFormats
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.PartialDownloadFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class StumpContentRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val stumpApiFactory: StumpApiFactory,
    private val stumpCredentialStore: StumpCredentialStore,
    private val cacheInvalidationDispatcher: DownloadedRemoteComicCacheInvalidationDispatcher,
) : RemoteDocumentDownloadRepository<StumpStoredSession>(
    context = context,
    sourceType = SourceType.STUMP,
    storageDirectoryName = "stump-documents",
    supportedFormats = ComicReaderFormats,
    sessionStore = stumpCredentialStore,
    missingSessionMessage = "Connect to Stump before downloading remote comics.",
) {
    private val sessionRequestRunner = StumpStoredSessionRequestRunner(stumpCredentialStore)

    override suspend fun download(
        session: StumpStoredSession,
        item: ReadableItem,
        remoteItemId: String,
        target: PartialDownloadFile,
    ) {
        runWithStoredSessionAddressFailover(
            sessionStore = stumpCredentialStore,
            session = session,
            createApi = stumpApiFactory::create,
        ) { api, activeSession ->
            sessionRequestRunner.run(
                api = api,
                session = activeSession,
            ) { accessToken ->
                api.downloadMediaFileResumably(
                    accessToken = accessToken,
                    mediaId = remoteItemId,
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
