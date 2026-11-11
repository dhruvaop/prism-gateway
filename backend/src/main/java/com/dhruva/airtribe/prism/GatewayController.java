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
    private final ManagedAccessService managed;
    private final BillingService billing;
    private final ObjectMapper json;
    public GatewayController(PrismService prism,ObjectMapper json,ManagedAccessService managed,BillingService billing){this.prism=prism;this.json=json;this.managed=managed;this.billing=billing;}

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<StreamingResponseBody> chat(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Map<String,Object> body){
        String requested=String.valueOf(body.getOrDefault("model","")); PrismService.Tenant tenant=null; PrismService.BudgetLease budgetLease=null; boolean streamOwnsLease=false; long started=System.nanoTime(); String id="req_"+UUID.randomUUID(); Long billedUser=null;
        try{
            tenant=prism.authenticate(auth);billedUser=billing.userIdForKey(tenant.key());if(billedUser!=null&&!billing.entitlement(billedUser).allowed())throw new PrismService.GatewayException(402,"subscription_required","Your free demo has ended. Subscribe to Pro or Enterprise to keep using the gateway."); prism.validateRequest(body); prism.applyPromptPolicies(tenant,body); budgetLease=prism.acquireBudgetLease(tenant); prism.admit(tenant,requested);if(billedUser!=null)billing.recordRequest(billedUser);
            if(Boolean.TRUE.equals(body.get("stream"))){
                PrismService.StreamSession stream=prism.beginStream(tenant,body);HttpHeaders h=new HttpHeaders();h.set("x-prism-provider",stream.provider());h.set("x-prism-model",stream.model());h.set("x-prism-model-creator",prism.modelCreator(stream.model()));h.set("x-prism-model-family",prism.modelFamily(stream.model()));h.set("x-prism-tier",prism.tierFor(stream.requested(),stream.task()));h.set("x-prism-cache",stream.cached()==null?"miss":"hit");h.set("x-prism-fallback",Boolean.toString(stream.fallback()));h.set("x-prism-cost-usd","0.000000000");h.set("x-prism-request-id",stream.requestId());if(stream.task()!=null)h.set("x-prism-task",stream.task());h.setContentType(MediaType.TEXT_EVENT_STREAM);h.setCacheControl(CacheControl.noCache());
                PrismService.BudgetLease streamLease=budgetLease;StreamingResponseBody bodyStream=output->{try{prism.writeStream(stream,output);}finally{streamLease.close();}};streamOwnsLease=true;return ResponseEntity.ok().headers(h).contentType(MediaType.TEXT_EVENT_STREAM).body(bodyStream);
            }
            PrismService.Result r=prism.complete(tenant,body);
            HttpHeaders h=new HttpHeaders();h.set("x-prism-provider",Objects.toString(r.provider(),"cache"));h.set("x-prism-model",r.model());h.set("x-prism-model-creator",prism.modelCreator(r.model()));h.set("x-prism-model-family",prism.modelFamily(r.model()));h.set("x-prism-tier",prism.tierFor(requested,r.task()));h.set("x-prism-cache",r.cacheHit()?"hit":"miss");h.set("x-prism-fallback",Boolean.toString(r.fallback()));h.set("x-prism-cost-usd",String.format(Locale.US,"%.9f",r.cost()));h.set("x-prism-request-id",r.requestId());if(r.task()!=null)h.set("x-prism-task",r.task());
            StreamingResponseBody response=output->output.write(json.writeValueAsBytes(r.payload()));return ResponseEntity.ok().headers(h).contentType(MediaType.APPLICATION_JSON).body(response);
        }catch(PrismService.GatewayException ex){
            LOGGER.warn("gateway_rejection requestId={} team={} model={} errorType={} httpStatus={}",id,tenant==null?"unknown":tenant.team(),requested,ex.type,ex.status);
            String status=switch(ex.type){case "authentication_error"->"rejected_auth";case "model_not_allowed"->"rejected_allowlist";case "rate_limit_exceeded"->"rejected_rate_limit";case "budget_exceeded"->"rejected_budget";case "subscription_required"->"rejected_subscription";case "invalid_request_error"->"rejected_invalid_request";case "guardrail_blocked"->"rejected_guardrail";default->ex.type.equals("upstream_error")?"upstream_error":ex.type.equals("not_found_error")?"rejected_model":"rejected_error";};
            prism.log(tenant,requested,status,null,null,0,0,0,"miss",false,null,0,(System.nanoTime()-started)/1_000_000,id);
            Map<String,Object> error=Map.of("error",Map.of("message",ex.getMessage(),"type",ex.type,"code",ex.type));StreamingResponseBody response=output->output.write(json.writeValueAsBytes(error));return ResponseEntity.status(ex.status).contentType(MediaType.APPLICATION_JSON).body(response);
        } finally { if(budgetLease!=null&&!streamOwnsLease)budgetLease.close(); }
    }

    @GetMapping("/health") public Map<String,Object> health(){return Map.of("status","ok","service","prism-gateway");}
    @GetMapping("/admin/usage")
    public Map<String,Object> usage(@RequestParam(required=false) String key){
        Map<String,Object> raw=prism.usage(key);
        if(raw.get("keys") instanceof List<?> rows){
            List<Map<String,Object>> clean=new ArrayList<>();
            for(Object row:rows)if(row instanceof Map<?,?> map)clean.add(withKeyRef(map,"key"));
            Map<String,Object> out=new LinkedHashMap<>(raw);out.put("keys",clean);return out;
        }
        return withKeyRef(raw,"key");
    }
    @PostMapping("/admin/usage/ref/{ref}/reset")
    public ResponseEntity<?> resetUsage(@PathVariable String ref){
        try{
            String key=managed.resolveKeyRef(ref);
            Map<String,Object> out=new LinkedHashMap<>(prism.resetUsage(key));
            out.remove("key");
            out.put("key_ref",ManagedAccessService.keyRef(key));
            out.put("key_hint",ManagedAccessService.keyHint(key));
            return ResponseEntity.ok(out);
        }catch(AuthService.AuthException ex){
            return ResponseEntity.status(ex.status()).body(Map.of("error",Map.of("message",ex.getMessage())));
        }
    }
    @GetMapping("/admin/logs")
    public Map<String,Object> logs(@RequestParam(required=false) String key,@RequestParam(defaultValue="100") int limit){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> row:prism.logs(key,limit))rows.add(withKeyRef(row,"virtual_key"));
        return Map.of("logs",rows);
    }
    @GetMapping("/admin/cache/stats") public Map<String,Object> cache(){return prism.cacheStats();}
    @GetMapping("/admin/cache/entries")
    public Map<String,Object> cacheEntries(@RequestParam(defaultValue="200") int limit){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> row:prism.cacheEntries(limit))rows.add(withKeyRef(row,"virtual_key"));
        return Map.of("entries",rows);
    }
    @GetMapping("/admin/cache/hits")
    public Map<String,Object> cacheHits(@RequestParam(defaultValue="200") int limit){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Map<String,Object> row:prism.cacheHits(limit))rows.add(withKeyRef(row,"virtual_key"));
        return Map.of("hits",rows);
    }
    @DeleteMapping("/admin/cache/entries/{id}") public Map<String,Object> deleteCacheEntry(@PathVariable long id){prism.deleteCacheEntry(id);return Map.of("deleted",true);}
    @PostMapping("/admin/cache/purge") public Map<String,Object> purgeCache(){return prism.purgeCache();}
    @GetMapping("/admin/providers/health") public Map<String,Object> providers(){return prism.providersHealth();}
    @GetMapping("/admin/overview")
    public Map<String,Object> overview(){
        Map<String,Object> raw=prism.usageOverview();
        Map<String,Object> out=new LinkedHashMap<>(raw);
        if(raw.get("keys") instanceof List<?> keys){
            List<Map<String,Object>> clean=new ArrayList<>();
            for(Object row:keys)if(row instanceof Map<?,?> map)clean.add(withKeyRef(map,"key"));
            out.put("keys",clean);
        }
        return out;
    }
    @GetMapping("/admin/router/eval") public Map<String,Object> routingEval(){return prism.routingEval();}
    @GetMapping("/admin/router/policy") public Map<String,Object> routingPolicy(){return prism.routingPolicy();}
    @GetMapping("/admin/routing/profiles") public Map<String,Object> routingProfiles(){return Map.of("profiles",prism.routingProfiles());}
    @GetMapping("/admin/keys") public List<Map<String,Object>> keys(){return managed.keys();}

    /**
     * Admin-only Playground transport. The browser sends an opaque key_ref and
     * an admin JWT; the virtual key, upstream provider, and model stay server-side.
     */
    @PostMapping("/admin/playground/chat")
    public ResponseEntity<StreamingResponseBody> playground(@RequestBody Map<String,Object> body){
        PrismService.Tenant tenant=null;PrismService.BudgetLease lease=null;boolean streamOwnsLease=false;
        try{
            String key=managed.resolveKeyRef(String.valueOf(body.getOrDefault("key_ref","")));
            tenant=prism.authenticateChat(key);
            Map<String,Object> gatewayBody=new LinkedHashMap<>(body);gatewayBody.remove("key_ref");
            String model=String.valueOf(gatewayBody.getOrDefault("model","auto"));
            prism.validateRequest(gatewayBody);
            prism.applyPromptPolicies(tenant,gatewayBody);
            lease=prism.acquireBudgetLease(tenant);
            prism.admit(tenant,model);
            PrismService.StreamSession stream=prism.beginStream(tenant,gatewayBody,true);
            HttpHeaders headers=new HttpHeaders();
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl(CacheControl.noCache());
            headers.set("x-prism-model-hidden","true");
            headers.set("x-prism-model",stream.model()==null?"unknown":stream.model());
            headers.set("x-prism-cache",stream.cached()==null?"miss":"hit");
            headers.set("x-prism-fallback",Boolean.toString(stream.fallback()));
            headers.set("x-prism-request-id",stream.requestId());
            PrismService.BudgetLease streamLease=lease;streamOwnsLease=true;
            StreamingResponseBody output=out->{try{prism.writeStream(stream,out);}finally{streamLease.close();}};
            return ResponseEntity.ok().headers(headers).body(output);
        }catch(AuthService.AuthException ex){
            return errorStream(ex.status(),ex.getMessage());
        }catch(PrismService.GatewayException ex){
            return errorStream(ex.status,prism.redactModelDetails(ex.getMessage()));
        }finally{
            if(lease!=null&&!streamOwnsLease)lease.close();
        }
    }

    private ResponseEntity<StreamingResponseBody> errorStream(int status,String message){
        Map<String,Object> error=Map.of("error",Map.of("message",message));
        StreamingResponseBody response=output->output.write(json.writeValueAsBytes(error));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /** Replaces a raw key column with its opaque reference and a short hint. */
    private static Map<String,Object> withKeyRef(Map<?,?> source,String field){
        Map<String,Object> copy=new LinkedHashMap<>();
        source.forEach((key,value)->copy.put(String.valueOf(key),value));
        Object raw=copy.remove(field);
        if(raw!=null&&!String.valueOf(raw).isBlank()){
            copy.put("key_ref",ManagedAccessService.keyRef(String.valueOf(raw)));
            copy.put("key_hint",ManagedAccessService.keyHint(String.valueOf(raw)));
        }
        return copy;
    }
}
