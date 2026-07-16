package dev.buhanzaz.rwms.platform.web;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatusCode;

public final class RwmsProblemDetailFactory {

    public ApiProblem create(
            URI type,
            String title,
            HttpStatusCode status,
            String detail,
            URI instance,
            String code,
            CorrelationContext correlation) {
        return new ApiProblem(
                type, title, status.value(), detail, instance, code, List.of(), correlation);
    }
}
