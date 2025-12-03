package nl.giejay.android.tv.immich.api

// Asegúrate de importar tu nueva clase (está en el mismo paquete, así que quizá no necesites import, 
// pero si el IDE se queja, verifica el paquete).
// import nl.giejay.android.tv.immich.api.LoggingInterceptor 

import nl.giejay.android.tv.immich.api.util.UnsafeOkHttpClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object ApiClientFactory {

    fun getClient(disableSsl: Boolean, apiKey: String, debugMode: Boolean): OkHttpClient {
        val apiKeyInterceptor = interceptor(apiKey)
        
        // 1. Crear el builder
        val builder = if (disableSsl)
            UnsafeOkHttpClient.unsafeOkHttpClient()
        else OkHttpClient.Builder()
        
        // 2. Añadir la API Key siempre
        builder.addInterceptor(apiKeyInterceptor)

        // 3. AÑADIR TU NUEVO INTERCEPTOR (Forzado para depurar)
        // Usamos tu clase LoggingInterceptor creada anteriormente.
        // Lo ponemos fuera del 'if (debugMode)' para asegurarnos de que salga en el logcat sí o sí.
        // builder.addInterceptor(LoggingInterceptor()) 

        return builder.build()
    }

    private fun interceptor(apiKey: String): Interceptor = Interceptor { chain ->
        val newRequest = chain.request().newBuilder()
            .addHeader("x-api-key", apiKey.trim())
            .build()
        chain.proceed(newRequest)
    }
}
