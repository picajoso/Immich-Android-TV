package nl.giejay.android.tv.immich.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
// CORRECCIÓN: El import correcto estaba en shared.prefs
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder 
import timber.log.Timber

class TimelineViewModel : ViewModel() {
    
    private val _buckets = MutableStateFlow<List<Bucket>>(emptyList())
    val buckets: StateFlow<List<Bucket>> = _buckets
    
    private val _selectedBucketId = MutableStateFlow<String?>(null)
    val selectedBucketId: StateFlow<String?> = _selectedBucketId
    
    private val _assets = MutableStateFlow<List<Asset>>(emptyList())
    val assets: StateFlow<List<Asset>> = _assets
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _isLoadingAssets = MutableStateFlow(false)
    val isLoadingAssets: StateFlow<Boolean> = _isLoadingAssets
    
    private var bucketsLoaded = false
    
    fun loadBuckets(apiClient: ApiClient, forceReload: Boolean = false) {
        if (bucketsLoaded && _buckets.value.isNotEmpty() && !forceReload) {
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.listBuckets("", PhotosOrder.NEWEST_OLDEST)
                }
                
                result.fold(
                    { error -> 
                        Timber.e("Error loading buckets: $error")
                    },
                    { bucketList -> 
                        _buckets.value = bucketList
                        bucketsLoaded = true
                        
                        // Seleccionar el primero por defecto si no hay selección previa
                        if (_selectedBucketId.value == null && bucketList.isNotEmpty()) {
                            selectBucket(bucketList.first().timeBucket, apiClient)
                        } else {
                            // Si ya había selección (al volver atrás), recargar sus assets
                            _selectedBucketId.value?.let { loadAssetsForBucket(it, apiClient) }
                        }
                    }
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Exception loading buckets")
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun selectBucket(bucketId: String, apiClient: ApiClient) {
        if (_selectedBucketId.value == bucketId && _assets.value.isNotEmpty()) {
            return
        }
        
        _selectedBucketId.value = bucketId
        loadAssetsForBucket(bucketId, apiClient)
    }
    
    private fun loadAssetsForBucket(bucketId: String, apiClient: ApiClient) {
        viewModelScope.launch {
            _isLoadingAssets.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.getAssetsForBucket("", bucketId, PhotosOrder.NEWEST_OLDEST)
                }
                
                result.fold(
                    { error ->
                        Timber.e("Error loading assets: $error")
                    },
                    { assetList ->
                        _assets.value = assetList
                    }
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Exception loading assets")
            } finally {
                _isLoadingAssets.value = false
            }
        }
    }
    
    fun forceReload(apiClient: ApiClient) {
        bucketsLoaded = false
        _buckets.value = emptyList()
        _assets.value = emptyList()
        loadBuckets(apiClient, forceReload = true)
    }
    
    fun getSelectedBucket(): Bucket? {
        val selectedId = _selectedBucketId.value ?: return null
        return _buckets.value.find { it.timeBucket == selectedId }
    }
}
