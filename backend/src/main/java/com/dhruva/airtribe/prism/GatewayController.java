package com.dhruva.airtribe.prism;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@RestController
public class GatewayController {
    private static final Logger LOGGER=LoggerFactory.getLogger(GatewayController.class);
    private final PrismService prism;
    private final ObjectMapper json;
    public GatewayController(PrismService prism,ObjectMapper json){this.prism=prism;this.json=json;}

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Map<String,Object> body){
        String requested=String.valueOf(body.getOrDefault("model","")); PrismService.Tenant tenant=null; long started=System.nanoTime(); String id="req_"+UUID.randomUUID();
        try{
            tenant=prism.authenticate(auth); prism.validateRequest(body); prism.admit(tenant,requested);
            if(Boolean.TRUE.equals(body.get("stream"))){
                PrismService.StreamSession stream=prism.beginStream(tenant,body);HttpHeaders h=new HttpHeaders();h.set("x-prism-provider",stream.provider());h.set("x-prism-cache",stream.cached()==null?"miss":"hit");h.set("x-prism-fallback",Boolean.toString(stream.fallback()));h.set("x-prism-request-id",stream.requestId());h.setContentType(MediaType.TEXT_EVENT_STREAM);h.setCacheControl(CacheControl.noCache());
                StreamingResponseBody bodyStream=output->prism.writeStream(stream,output);return ResponseEntity.ok().headers(h).contentType(MediaType.TEXT_EVENT_STREAM).body(bodyStream);
            }
            PrismService.Result r=prism.complete(tenant,body);
            HttpHeaders h=new HttpHeaders();h.set("x-prism-provider",Objects.toString(r.provider(),"cache"));h.set("x-prism-cache",r.cacheHit()?"hit":"miss");h.set("x-prism-fallback",Boolean.toString(r.fallback()));h.set("x-prism-cost-usd",String.format(Locale.US,"%.9f",r.cost()));h.set("x-prism-request-id",r.requestId());
            StreamingResponseBody response=output->output.write(json.writeValueAsBytes(r.payload()));return ResponseEntity.ok().headers(h).contentType(MediaType.APPLICATION_JSON).body(response);
        }catch(PrismService.GatewayException ex){
            LOGGER.warn("gateway_rejection requestId={} team={} model={} errorType={} httpStatus={}",id,tenant==null?"unknown":tenant.team(),requested,ex.type,ex.status);
            String status=switch(ex.type){case "authentication_error"->"rejected_auth";case "model_not_allowed"->"rejected_allowlist";case "rate_limit_exceeded"->"rejected_rate_limit";case "budget_exceeded"->"rejected_budget";case "invalid_request_error"->"rejected_invalid_request";default->ex.type.equals("upstream_error")?"upstream_error":ex.type.equals("not_found_error")?"rejected_model":"rejected_error";};
            prism.log(tenant,requested,status,null,null,0,0,0,"miss",false,null,0,(System.nanoTime()-started)/1_000_000,id);
            Map<String,Object> error=Map.of("error",Map.of("message",ex.getMessage(),"type",ex.type,"code",ex.type));StreamingResponseBody response=output->output.write(json.writeValueAsBytes(error));return ResponseEntity.status(ex.status).contentType(MediaType.APPLICATION_JSON).body(response);
        }
    }

    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","service","prism-gateway");}
    @GetMapping("/admin/usage") public Map<String,Object> usage(@RequestParam(required=false) String key){return prism.usage(key);}
    @GetMapping("/admin/logs") public Map<String,Object> logs(@RequestParam(required=false) String key,@RequestParam(defaultValue="100") int limit){return Map.of("logs",prism.logs(key,limit));}
    @GetMapping("/admin/cache/stats") public Map<String,Object> cache(){return prism.cacheStats();}
    @GetMapping("/admin/providers/health") public Map<String,Object> providers(){return prism.providersHealth();}
    @GetMapping("/admin/overview") public Map<String,Object> overview(){return prism.usageOverview();}
    @GetMapping("/admin/router/eval") public Map<String,Object> routingEval(){return prism.routingEval();}
    @GetMapping("/admin/keys") public List<Map<String,Object>> keys(){List<Map<String,Object>> out=new ArrayList<>();for(PrismService.Tenant t:prism.tenantList())out.add(Map.of("key",t.key(),"team",t.team(),"budget_usd",t.budget(),"rpm_limit",t.rpm(),"allowlist",t.allowlist(),"cache_enabled",t.cacheEnabled()));return out;}
}
