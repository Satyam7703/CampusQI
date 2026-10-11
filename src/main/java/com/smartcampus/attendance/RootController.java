package com.smartcampus.attendance;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
class RootController {
  @GetMapping("/")
  RedirectView login() {
    return new RedirectView("/common-login.html?v=20261010-3");
  }
}
