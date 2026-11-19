package com.example.unifiedreader

import android.content.Context
import com.example.unifiedreader.core.model.AppReadableFormats
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.PartialDownloadFile

interface GrimmoryContentDataSource : com.example.unifiedreader.feature.downloads.RemoteItemDownloadDataSource

class GrimmoryContentRepository(
    context: Context,
    private val grimmoryApiFactory: GrimmoryApiFactory,
    private val grimmoryCredentialStore: GrimmoryCredentialStore,
    private val cacheInvalidationDispatcher: DownloadedRemoteComicCacheInvalidationDispatcher,
) : RemoteDocumentDownloadRepository<GrimmoryStoredSession>(
    context = context,
    sourceType = SourceType.GRIMMORY,
    storageDirectoryName = "grimmory-documents",
    supportedFormats = AppReadableFormats,
    sessionStore = grimmoryCredentialStore,
    missingSessionMessage = "Connect to the active Grimmory server before opening remote books.",
), GrimmoryContentDataSource {
    private val sessionRequestRunner = GrimmoryStoredSessionRequestRunner(grimmoryCredentialStore)

    override suspend fun download(
        session: GrimmoryStoredSession,
        item: ReadableItem,
        remoteItemId: String,
        target: PartialDownloadFile,
    ) {
        runWithStoredSessionAddressFailover(
            sessionStore = grimmoryCredentialStore,
            session = session,
            createApi = grimmoryApiFactory::create,
        ) { api, activeSession ->
            sessionRequestRunner.run(
                api = api,
                session = activeSession,
            ) { accessToken ->
                api.downloadBookContentResumably(
                    accessToken = accessToken,
                    bookId = remoteItemId,
                    bookType = item.remoteFileType ?: item.format.name,
                    target = target,
                )
            }
        }
    }

    override fun cacheFileName(
        item: ReadableItem,
        extension: String,
    ): String = item.grimmoryCacheFileName(extension)

    override fun onStoredDocumentChanged(
        item: ReadableItem,
    ) {
        cacheInvalidationDispatcher.invalidate(item.id)
    }
}
