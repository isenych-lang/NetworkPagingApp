package com.example.networkapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ================= 1. DOMAIN LAYER (Бизнес-логика) =================
data class Product(val id: Int, val title: String, val price: Double)

// ================= 2. DATA LAYER (DTO, API, PagingSource) =================
data class ProductDto(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String,
    @Json(name = "price") val price: Double
)

data class ProductResponse(val products: List<ProductDto>)

// Mapper: Перевод данных из слоя сети (Data) в слой бизнес-логики (Domain)
fun ProductDto.toDomain() = Product(id = id, title = title, price = price)

interface ProductApiService {
    @GET("products")
    suspend fun getProducts(
        @Query("skip") skip: Int,
        @Query("limit") limit: Int
    ): ProductResponse
}

class ProductPagingSource(private val apiService: ProductApiService) : PagingSource<Int, Product>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Product> {
        val position = params.key ?: 0
        return try {
            val response = apiService.getProducts(skip = position, limit = params.loadSize)
            val domainProducts = response.products.map { it.toDomain() }
            
            LoadResult.Page(
                data = domainProducts,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (response.products.isEmpty()) null else position + params.loadSize
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
    override fun getRefreshKey(state: PagingState<Int, Product>): Int? = state.anchorPosition
}

// Network Client Setup
object NetworkClient {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val interceptor = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
    private val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

    val apiService: ProductApiService = Retrofit.Builder()
        .baseUrl("https://dummyjson.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ProductApiService::class.java)
}

// ================= 3. PRESENTATION LAYER (ViewModel) =================
class ProductViewModel : ViewModel() {
    val pagedProducts: Flow<PagingData<Product>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false)
    ) {
        ProductPagingSource(NetworkClient.apiService)
    }.flow.cachedIn(viewModelScope)
}

// ================= 4. UI LAYER (Jetpack Compose) =================
@Composable
fun ProductItem(product: Product) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(product.title, style = MaterialTheme.typography.titleMedium)
            Text("${product.price} USD")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(viewModel: ProductViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val products = viewModel.pagedProducts.collectAsLazyPagingItems()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Product List (Paging 3)") }) }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            items(products.itemCount) { index ->
                products[index]?.let { ProductItem(it) }
            }
            products.apply {
                when {
                    loadState.refresh is LoadState.Loading || loadState.append is LoadState.Loading -> {
                        item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    }
                    loadState.append is LoadState.Error -> {
                        item { Text("Error loading more", color = Color.Red) }
                    }
                }
            }
        }
    }
}

// ================= 5. ENTRY POINT =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProductListScreen()
            }
        }
    }
}
