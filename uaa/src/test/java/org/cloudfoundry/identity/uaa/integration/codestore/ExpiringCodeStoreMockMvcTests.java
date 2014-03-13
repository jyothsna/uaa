package org.cloudfoundry.identity.uaa.integration.codestore;
import static org.junit.Assert.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;

import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.config.YamlServletProfileInitializer;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.XmlWebApplicationContext;

import com.googlecode.flyway.core.Flyway;
public class ExpiringCodeStoreMockMvcTests {

    private static XmlWebApplicationContext webApplicationContext;
    private static MockMvc mockMvc;
    private static TestClient testClient;
    private static String loginToken;

    @BeforeClass
    public static void setUp() throws Exception {
        webApplicationContext = new XmlWebApplicationContext();
        webApplicationContext.setServletContext(new MockServletContext());
        webApplicationContext.setConfigLocation("file:./src/main/webapp/WEB-INF/spring-servlet.xml");
        new YamlServletProfileInitializer().initialize(webApplicationContext);
        webApplicationContext.refresh();
        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean(FilterChainProxy.class);

        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilter(springSecurityFilterChain).build();
        testClient = new TestClient(mockMvc);
        loginToken = testClient.getOAuthAccessToken("login", "loginsecret", "client_credentials", "oauth.login");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        Flyway flyway = (Flyway)webApplicationContext.getBean(Flyway.class);
        flyway.clean();
        webApplicationContext.close();
    }

    @Test
    public void testGenerateCode() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+60000);
        ExpiringCode code = new ExpiringCode(null,ts,"{}");
        
       
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").exists())
            .andExpect(jsonPath("$.expiresAt").value(ts.getTime()))
            .andExpect(jsonPath("$.data").value("{}"));
        
    }
    
    @Test
    public void testGenerateCodeWithInvalidScope() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+60000);
        ExpiringCode code = new ExpiringCode(null,ts,"{}");
        TestClient testClient = new TestClient(mockMvc);
        String loginToken = testClient.getOAuthAccessToken("admin", "adminsecret", "client_credentials", "");
       
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isForbidden());
    }
    
    @Test
    public void testGenerateCodeAnonymous() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+60000);
        ExpiringCode code = new ExpiringCode(null,ts,"{}");
        
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isUnauthorized());
    }
    
    @Test
    public void testGenerateCodeWithNullData() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+60000);
        ExpiringCode code = new ExpiringCode(null,ts,null);
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isBadRequest());
        
    }
    
    @Test
    public void testGenerateCodeWithNullExpiresAt() throws Exception {
        ExpiringCode code = new ExpiringCode(null,null,"{}");
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isBadRequest());
        
    }
    
    @Test
    public void testGenerateCodeWithExpiresAtInThePast() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()-60000);
        ExpiringCode code = new ExpiringCode(null,ts,null);
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        mockMvc.perform(post)
            .andDo(print())
            .andExpect(status().isBadRequest());
            
    }
    
    @Test
    public void testRetrieveCode() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+60000);
        ExpiringCode code = new ExpiringCode(null,ts,"{}");
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        MvcResult result = mockMvc.perform(post)
                .andDo(print())
                .andExpect(status().isCreated())
                .andReturn();
        
        ExpiringCode rc = new ObjectMapper().readValue(result.getResponse().getContentAsString(), ExpiringCode.class);
        
        MockHttpServletRequestBuilder get = get("/Codes/"+rc.getCode())
                .header("Authorization", "Bearer " + loginToken)
                .accept(MediaType.APPLICATION_JSON);
        
        result = mockMvc.perform(get)
                .andDo(print())
                .andExpect(status().isOk())
                .andReturn();
        
        ExpiringCode rc1 = new ObjectMapper().readValue(result.getResponse().getContentAsString(), ExpiringCode.class);
        
        assertEquals(rc,rc1);
    }
    
    @Test
    public void testRetrieveCodeThatIsExpired() throws Exception {
        Timestamp ts = new Timestamp(System.currentTimeMillis()+1000);
        ExpiringCode code = new ExpiringCode(null,ts,"{}");
        String requestBody = new ObjectMapper().writeValueAsString(code);
        MockHttpServletRequestBuilder post = post("/Codes")
                .header("Authorization", "Bearer " + loginToken)
                .contentType(APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody);

        MvcResult result = mockMvc.perform(post)
                .andDo(print())
                .andExpect(status().isCreated())
                .andReturn();
        
        ExpiringCode rc = new ObjectMapper().readValue(result.getResponse().getContentAsString(), ExpiringCode.class);
        Thread.sleep(1001);
        MockHttpServletRequestBuilder get = get("/Codes/"+rc.getCode())
                .header("Authorization", "Bearer " + loginToken)
                .accept(MediaType.APPLICATION_JSON);
        
        result = mockMvc.perform(get)
                .andDo(print())
                .andExpect(status().isNotFound())
                .andReturn();
    }
    
}


