package net.micapum.neopen.core.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ApiFactoryReuseTest {
    @Test fun normalizesOriginAndReusesServicesWithoutMixingPathsOrInjectedClients() {
        val first = ApiFactory.create("https://EXAMPLE.com:443/neopen")
        assertSame(first, ApiFactory.create("https://example.com/neopen/"))
        assertNotSame(first, ApiFactory.create("https://example.com/other/"))
        assertNotSame(first, ApiFactory.create("https://another.example/neopen/"))
        val custom = OkHttpClient()
        assertNotSame(first, ApiFactory.create("https://example.com/neopen/", custom))
        assertNotSame(ApiFactory.create("https://example.com/neopen/", custom), ApiFactory.create("https://example.com/neopen/", custom))
    }
}
