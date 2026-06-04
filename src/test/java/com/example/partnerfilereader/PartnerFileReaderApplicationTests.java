package com.example.partnerfilereader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sftp.enabled=false")
@AutoConfigureMockMvc
class PartnerFileReaderApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void demoPagesLoad() throws Exception {
		mockMvc.perform(get("/reconciliation")).andExpect(status().isOk());
		mockMvc.perform(get("/")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/reconciliation"));
		mockMvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/reconciliation"));
		mockMvc.perform(get("/sftp")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/reconciliation"));
		mockMvc.perform(get("/data-containers")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/reconciliation"));
		mockMvc.perform(get("/reconciliation-results")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/reconciliation"));
	}

}
