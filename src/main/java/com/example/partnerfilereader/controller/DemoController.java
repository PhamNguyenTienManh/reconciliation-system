package com.example.partnerfilereader.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DemoController {

    @GetMapping("/")
    public String home() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/sftp")
    public String sftp() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/data-containers")
    public String dataContainers() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/reconciliation-results")
    public String reconciliationResults() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/reconciliation")
    public String reconciliation() {
        return "reconciliation";
    }
}
