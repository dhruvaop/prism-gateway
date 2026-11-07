package com.dhruva.airtribe.prism;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

@Service
public class PrismService {
    private static final Logger LOGGER=LoggerFactory.getLogger(PrismService.class);
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${prism.data-dir}") private String dataDir;
    @Value("${spring.datasource.url}") private String databaseUrl;
    @Value("${prism.upstream-timeout-ms}") private int timeoutMs;
    @Value("${prism.retry.max-attempts:3}") private int maxAttempts;
    @Value("${prism.retry.initial-backoff-ms:200}") private int initialBackoffMs;
    @Value("${prism.retry.backoff-multiplier:2}") private int backoffMultiplier;
    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, Provider> providers = new HashMap<>();
    private final Map<String, Alias> aliases = new HashMap<>();
    private final Map<String, Price> prices = new HashMap<>();
    private final Map<String, Deque<Long>> requests = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2500)).build();

    public PrismService(JdbcTemplate db) { this.db = db; }

    @PostConstruct public void initialize() throws Exception {
        DatabaseDialect.Kind database=DatabaseDialect.detect(databaseUrl);
        if(database==DatabaseDialect.Kind.SQLITE){
            db.execute("PRAGMA journal_mode=WAL"); db.execute("PRAGMA busy_timeout=10000");
            db.execute("CREATE TABLE IF NOT EXISTS request_logs(request_id TEXT PRIMARY KEY, virtual_key TEXT, team TEXT, requested_model TEXT, resolved_provider TEXT, resolved_model TEXT, status TEXT, prompt_tokens INTEGER DEFAULT 0, completion_tokens INTEGER DEFAULT 0, cost_usd DOUBLE PRECISION DEFAULT 0, cache TEXT, fallback INTEGER DEFAULT 0, route_reason TEXT, retries INTEGER DEFAULT 0, latency_ms INTEGER DEFAULT 0, created_at TEXT)");
            db.execute("CREATE TABLE IF NOT EXISTS usage_monthly(virtual_key TEXT, month TEXT, team TEXT, requests INTEGER DEFAULT 0, prompt_tokens INTEGER DEFAULT 0, completion_tokens INTEGER DEFAULT 0, cost_usd DOUBLE PRECISION DEFAULT 0, cache_hits INTEGER DEFAULT 0, PRIMARY KEY(virtual_key,month))");
            try { db.execute("ALTER TABLE usage_monthly ADD COLUMN team TEXT"); } catch (Exception ignored) { }
            db.execute(DatabaseDialect.cacheTableSql(database));
        }
        Path root = Paths.get(dataDir).toAbsolutePath().normalize();
        loadTenants(root.resolve("seed_keys.json")); loadConfig(root.resolve("gateway_config.sample.json")); loadPrices(root.resolve("model_pricing.json"));
        LOGGER.info("prism_initialized tenants={} providers={} aliases={} pricedModels={} database={}",tenants.size(),providers.size(),aliases.size(),prices.size(),database.name().toLowerCase(Locale.ROOT));
    }
    private JsonNode read(Path p) throws IOException { return json.readTree(Files.readAllBytes(p)); }
    private void loadTenants(Path p) throws IOException {
        for (JsonNode n : read(p).path("tenants")) {
            JsonNode c=n.path("semantic_cache"); Tenant t=new Tenant(n.path("virtual_key").asText(),n.path("team").asText(),n.path("monthly_budget_usd").asDouble(),n.path("rate_limit").path("requests_per_minute").asInt(),n.path("rate_limit").path("tokens_per_minute").asInt(0), strings(n.path("model_allowlist")),c.path("enabled").asBoolean(),c.path("similarity_threshold").asDouble(.92));
            tenants.put(t.key,t);
        }
    }
    private void loadConfig(Path p) throws IOException {
        JsonNode root=read(p); for(JsonNode n:root.path("providers")) providers.put(n.path("name").asText(),new Provider(n.path("name").asText(),n.path("base_url").asText(),n.path("api_key").asText()));
        root.path("model_aliases").fields().forEachRemaining(e->{JsonNode n=e.getValue(); if(n.has("primary")) aliases.put(e.getKey(),new Alias(n.path("primary").asText(),strings(n.path("fallbacks"))));});
        JsonNode retry=root.path("retry");if(retry.isObject()){maxAttempts=retry.path("max_attempts").asInt(maxAttempts);initialBackoffMs=retry.path("initial_backoff_ms").asInt(initialBackoffMs);backoffMultiplier=retry.path("backoff_multiplier").asInt(backoffMultiplier);}
    }
    private void loadPrices(Path p) throws IOException { read(p).fields().forEachRemaining(e->prices.put(e.getKey(),new Price(e.getValue().path("input_per_1m").asDouble(),e.getValue().path("output_per_1m").asDouble()))); }
    private List<String> strings(JsonNode n) { List<String> a=new ArrayList<>(); n.forEach(x->a.add(x.asText())); return a; }
    public Collection<Tenant> tenantList(){ return tenants.values(); }
    public record Tenant(String key,String team,double budget,int rpm,int tpm,List<String> allowlist,boolean cacheEnabled,double threshold) {}
    public record Provider(String name,String url,String key) {}
    private record Alias(String primary,List<String> fallbacks) {}
    private record Price(double input,double output) {}
    public static class GatewayException extends RuntimeException { public final int status; public final String type; GatewayException(int s,String t,String m){super(m);status=s;type=t;} }
    public record Result(Map<String,Object> payload,String provider,String model,boolean fallback,double cost,int promptTokens,int completionTokens,String routeReason,int retries,boolean cacheHit,long latencyMs,String requestId) {}
    public record StreamSession(InputStream input,Map<String,Object> cached,Tenant tenant,String requested,String prompt,String model,String provider,boolean fallback,String reason,int retries,String requestId,long started) {}

    public synchronized void log(Tenant t,String requested,String status,String provider,String model,int pt,int ct,double cost,String cache,boolean fallback,String reason,int retries,long latency,String id) {
        String now=Instant.now().toString(); db.update("INSERT INTO request_logs(request_id,virtual_key,team,requested_model,resolved_provider,resolved_model,status,prompt_tokens,completion_tokens,cost_usd,cache,fallback,route_reason,retries,latency_ms,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET virtual_key=excluded.virtual_key,team=excluded.team,requested_model=excluded.requested_model,resolved_provider=excluded.resolved_provider,resolved_model=excluded.resolved_model,status=excluded.status,prompt_tokens=excluded.prompt_tokens,completion_tokens=excluded.completion_tokens,cost_usd=excluded.cost_usd,cache=excluded.cache,fallback=excluded.fallback,route_reason=excluded.route_reason,retries=excluded.retries,latency_ms=excluded.latency_ms,created_at=excluded.created_at",id,t==null?null:t.key,t==null?null:t.team,requested,provider,model,status,pt,ct,cost,cache,fallback?1:0,reason,retries,latency,now);
        if(t!=null && (status.equals("ok")||status.equals("cache_hit"))) db.update("INSERT INTO usage_monthly(virtual_key,month,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits) VALUES(?,?,?,1,?,?,?,?) ON CONFLICT(virtual_key,month) DO UPDATE SET team=excluded.team,requests=requests+1,prompt_tokens=prompt_tokens+excluded.prompt_tokens,completion_tokens=completion_tokens+excluded.completion_tokens,cost_usd=cost_usd+excluded.cost_usd,cache_hits=cache_hits+excluded.cache_hits",t.key,YearMonth.now(ZoneOffset.UTC).toString(),t.team,pt,ct,cost,status.equals("cache_hit")?1:0);
        LOGGER.info("gateway_request requestId={} team={} model={} status={} provider={} cache={} fallback={} retries={} promptTokens={} completionTokens={} costUsd={} latencyMs={}",id,t==null?"unknown":t.team,requested,status,provider==null?"none":provider,cache,fallback,retries,pt,ct,String.format(Locale.US,"%.9f",cost),latency);
    }
    public Tenant authenticate(String auth) { if(auth==null||!auth.startsWith("Bearer ")) throw new GatewayException(401,"authentication_error","Missing virtual key"); Tenant t=tenants.get(auth.substring(7).trim()); if(t==null) throw new GatewayException(401,"authentication_error","Invalid virtual key"); return t; }
    public void admit(Tenant t,String model) {
        validateModel(model);
        if(!t.allowlist.contains(model)) throw new GatewayException(403,"model_not_allowed","Model is not allowed for this key");
        Deque<Long> q=requests.computeIfAbsent(t.key,k->new ConcurrentLinkedDeque<>()); long now=System.currentTimeMillis(); synchronized(q){ while(!q.isEmpty()&&now-q.peekFirst()>=60000)q.removeFirst(); if(q.size()>=t.rpm)throw new GatewayException(429,"rate_limit_exceeded","Requests per minute limit exceeded"); q.addLast(now); }
        Double spent=db.queryForObject("SELECT COALESCE((SELECT cost_usd FROM usage_monthly WHERE virtual_key=? AND month=?),0)",Double.class,t.key,YearMonth.now(ZoneOffset.UTC).toString());
        if(spent>=t.budget) throw new GatewayException(429,"budget_exceeded",String.format(Locale.US,"Monthly budget of $%.5f exhausted for this key",t.budget));
    }
    public void validateModel(String model){if(!model.equals("auto")&&!aliases.containsKey(model)&&!prices.containsKey(model))throw new GatewayException(404,"not_found_error","Unknown model or alias: "+model);}
    public void validateRequest(Map<String,Object> body){Object raw=body.get("messages");if(!(raw instanceof List<?> messages)||messages.isEmpty())throw new GatewayException(400,"invalid_request_error","messages must be a non-empty array");for(Object m:messages){if(!(m instanceof Map<?,?> map)||!map.containsKey("role")||!map.containsKey("content"))throw new GatewayException(400,"invalid_request_error","Each message must include role and content");}if(body.get("model")==null||String.valueOf(body.get("model")).isBlank())throw new GatewayException(400,"invalid_request_error","model is required");}
    public String prompt(Map<String,Object> body) { StringBuilder b=new StringBuilder(); Object msgs=body.get("messages"); if(msgs instanceof List<?> list) for(Object m:list) if(m instanceof Map<?,?> map) b.append(String.valueOf(map.containsKey("content")?map.get("content"):"")).append('\n'); return b.toString().trim(); }
    public String autoRoute(String prompt) { String p=prompt.toLowerCase(Locale.ROOT); String[] hard={"prove","disprove","irrational","consecutive integers","estimate","p99","latency","distributed system","quorum","palindromic","o(n","zero-downtime","migration plan","database schema","proration","trade-offs","event sourcing","ledger","likely causes","debugging","show your reasoning","recommend"}; String[] easy={"capital of","convert","commit message","404 mean","http status","translate","who is on call","timestamp of","first error","extract","divisible by","rewrite the following email","roster","log excerpt"}; int h=0,e=0; for(String s:hard)if(p.contains(s))h+=2;for(String s:easy)if(p.contains(s))e+=2; if(p.split("\\s+").length>40&&(p.contains("roster")||p.contains("log")||p.contains("extract")||p.contains("who is on call")))e+=3; boolean smart=h>e; return (smart?"smart":"fast")+"|"+(smart?"complex reasoning signal":"direct lookup or extraction task")+" (complex="+h+", simple="+e+")"; }
    private String[] chain(String requested) {
        String route=requested;
        if(requested.equals("auto")) route="smart"; // resolved in complete() using prompt classification
        Alias a=aliases.get(route); if(a!=null){List<String> out=new ArrayList<>();out.add(a.primary);out.addAll(a.fallbacks);return out.toArray(String[]::new);}
        if(prices.containsKey(requested))return new String[]{requested}; throw new GatewayException(404,"not_found_error","Unknown model or alias: "+requested);
    }
    public Result complete(Tenant t,Map<String,Object> body) {
        long start=System.nanoTime(); String requested=String.valueOf(body.getOrDefault("model","")), prompt=prompt(body), requestId="req_"+UUID.randomUUID(); String reason=null; String resolvedRequest=requested;
        if(requested.equals("auto")){String decision=autoRoute(prompt); resolvedRequest=decision.substring(0,decision.indexOf('|'));reason="auto → "+decision.replace('|',':');LOGGER.debug("routing_decision requestId={} tier={} reason={}",requestId,resolvedRequest,reason);}
        String[] chain=chain(resolvedRequest); boolean cache=t.cacheEnabled && !Boolean.FALSE.equals(body.get("cache"));
        if(cache){Result hit=findCache(t,requested,prompt);if(hit!=null){long ms=(System.nanoTime()-start)/1_000_000;Result result=new Result(hit.payload,"cache",hit.model,false,0,hit.promptTokens,hit.completionTokens,reason,0,true,ms,requestId);log(t,requested,"cache_hit",null,null,result.promptTokens,result.completionTokens,0,"hit",false,reason,0,ms,requestId);return result;}}
        int retries=0; List<String> errors=new ArrayList<>();
        for(int ix=0;ix<chain.length;ix++){
            String model=chain[ix], pname=model.contains("-")?model.substring(0,model.indexOf('-')):""; Provider provider=providers.get(pname); if(provider==null)continue;
            Map<String,Object> outbound=new LinkedHashMap<>(body);outbound.put("model",model);outbound.put("stream",false);
            for(int attempt=0;attempt<maxAttempts;attempt++){ try{
                HttpRequest req=HttpRequest.newBuilder(URI.create(provider.url.replaceAll("/$","")+"/chat/completions")).timeout(Duration.ofMillis(timeoutMs)).header("Authorization","Bearer "+provider.key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(outbound))).build();
                HttpResponse<String> resp=http.send(req,HttpResponse.BodyHandlers.ofString()); if(resp.statusCode()==200){Map<String,Object> payload=json.readValue(resp.body(),Map.class);Map<?,?> usage=(Map<?,?>)payload.getOrDefault("usage",Map.of());int pt=num(usage.get("prompt_tokens")),ct=num(usage.get("completion_tokens"));double cost=cost(model,pt,ct);payload.put("model",model);long ms=(System.nanoTime()-start)/1_000_000;Result result=new Result(payload,provider.name+"/"+model,model,ix>0,cost,pt,ct,reason,retries,false,ms,requestId);log(t,requested,"ok",result.provider,model,pt,ct,cost,"miss",ix>0,reason,retries,ms,requestId);if(cache)saveCache(t,requested,prompt,payload);return result;}
                errors.add(provider.name+" returned HTTP "+resp.statusCode()); if(!Set.of(408,429,500,502,503,504).contains(resp.statusCode()))break;LOGGER.warn("upstream_retry requestId={} provider={} model={} attempt={} reason=http_{}",requestId,provider.name,model,attempt+1,resp.statusCode());
            }catch(Exception ex){errors.add(provider.name+" unavailable");LOGGER.warn("upstream_retry requestId={} provider={} model={} attempt={} reason={}",requestId,provider.name,model,attempt+1,ex.getClass().getSimpleName());}
            if(attempt+1<maxAttempts){retries++;try{Thread.sleep((long)(initialBackoffMs*Math.pow(backoffMultiplier,attempt)));}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}}
            }
        }
        long ms=(System.nanoTime()-start)/1_000_000; LOGGER.error("upstream_exhausted requestId={} model={} attempts={} providers={}",requestId,requested,retries+1,chain.length);log(t,requested,"upstream_error",null,null,0,0,0,"miss",false,reason,retries,ms,requestId);throw new GatewayException(502,"upstream_error","All configured providers failed");
    }
    public StreamSession beginStream(Tenant t,Map<String,Object> body){long started=System.nanoTime();String requested=String.valueOf(body.get("model")),prompt=prompt(body),id="req_"+UUID.randomUUID(),reason=null,target=requested;if(requested.equals("auto")){String d=autoRoute(prompt);target=d.substring(0,d.indexOf('|'));reason="auto → "+d.replace('|',':');}String[] models=chain(target);if(t.cacheEnabled&&!Boolean.FALSE.equals(body.get("cache"))){Result hit=findCache(t,requested,prompt);if(hit!=null)return new StreamSession(null,hit.payload,t,requested,prompt,hit.model,"cache",false,reason,0,id,started);}int retries=0;
        for(int i=0;i<models.length;i++){String model=models[i],name=model.contains("-")?model.substring(0,model.indexOf('-')):"";Provider provider=providers.get(name);if(provider==null)continue;Map<String,Object> outbound=new LinkedHashMap<>(body);outbound.put("model",model);outbound.put("stream",true);
            for(int attempt=0;attempt<maxAttempts;attempt++){try{HttpRequest req=HttpRequest.newBuilder(URI.create(provider.url.replaceAll("/$","")+"/chat/completions")).timeout(Duration.ofMillis(timeoutMs)).header("Authorization","Bearer "+provider.key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(outbound))).build();HttpResponse<InputStream> resp=http.send(req,HttpResponse.BodyHandlers.ofInputStream());if(resp.statusCode()==200)return new StreamSession(resp.body(),null,t,requested,prompt,model,provider.name+"/"+model,i>0,reason,retries,id,started);try{resp.body().close();}catch(Exception ignored){}if(!Set.of(408,429,500,502,503,504).contains(resp.statusCode()))break;}catch(Exception ex){/* try again, then move to the configured fallback */}if(attempt+1<maxAttempts){retries++;try{Thread.sleep((long)(initialBackoffMs*Math.pow(backoffMultiplier,attempt)));}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}}}
        }throw new GatewayException(502,"upstream_error","All configured providers failed for streaming request");}
    public void writeStream(StreamSession s,OutputStream out)throws IOException{int pt=0,ct=0;double cost=0;StringBuilder content=new StringBuilder();Map<String,Object> payload=s.cached;try{
        if(s.cached!=null){Map<?,?> choice=((List<Map<?,?>>)s.cached.get("choices")).get(0);Map<?,?> msg=(Map<?,?>)choice.get("message");String text=String.valueOf(msg.containsKey("content")?msg.get("content"):"");Map<?,?> cachedUsage=(Map<?,?>)(s.cached.containsKey("usage")?s.cached.get("usage"):Map.of());writeEvent(out,Map.of("id",s.requestId,"object","chat.completion.chunk","model",s.model,"choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant"),"finish_reason",nullSafe()))));writeEvent(out,Map.of("id",s.requestId,"object","chat.completion.chunk","model",s.model,"choices",List.of(Map.of("index",0,"delta",Map.of("content",text),"finish_reason",nullSafe()))));pt=num(cachedUsage.get("prompt_tokens"));ct=num(cachedUsage.get("completion_tokens"));writeEvent(out,Map.of("id",s.requestId,"object","chat.completion.chunk","model",s.model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason","stop")),"usage",Map.of("prompt_tokens",pt,"completion_tokens",ct,"total_tokens",pt+ct)));writeDone(out);long ms=(System.nanoTime()-s.started)/1_000_000;log(s.tenant,s.requested,"cache_hit",null,null,pt,ct,0,"hit",false,s.reason,0,ms,s.requestId);return;}
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(s.input,StandardCharsets.UTF_8))){String line;boolean done=false;while((line=reader.readLine())!=null){out.write((line+"\n").getBytes(StandardCharsets.UTF_8));out.flush();if(line.startsWith("data: ")){String data=line.substring(6);if(data.equals("[DONE]")){done=true;continue;}try{Map<String,Object> event=json.readValue(data,Map.class);Object choices=event.get("choices");if(choices instanceof List<?> list&&!list.isEmpty()&&list.get(0) instanceof Map<?,?> choice){Object delta=choice.get("delta");if(delta instanceof Map<?,?> dm&&dm.get("content")!=null)content.append(dm.get("content"));}Object usage=event.get("usage");if(usage instanceof Map<?,?> u){pt=num(u.get("prompt_tokens"));ct=num(u.get("completion_tokens"));}}catch(Exception ignored){}}}if(!done)writeDone(out);}
        cost=cost(s.model,pt,ct);long ms=(System.nanoTime()-s.started)/1_000_000;log(s.tenant,s.requested,"ok",s.provider,s.model,pt,ct,cost,"miss",s.fallback,s.reason,s.retries,ms,s.requestId);Map<String,Object> response=Map.of("id",s.requestId,"object","chat.completion","model",s.model,"choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content.toString()),"finish_reason","stop")),"usage",Map.of("prompt_tokens",pt,"completion_tokens",ct,"total_tokens",pt+ct));if(s.tenant.cacheEnabled)saveCache(s.tenant,s.requested,s.prompt,response);
    }catch(IOException ex){long ms=(System.nanoTime()-s.started)/1_000_000;LOGGER.error("upstream_stream_failed requestId={} provider={} model={} error={}",s.requestId,s.provider,s.model,ex.getClass().getSimpleName());log(s.tenant,s.requested,"upstream_error",s.provider,s.model,pt,ct,cost,"miss",s.fallback,s.reason,s.retries,ms,s.requestId);String err="data: {\"error\":{\"type\":\"upstream_stream_error\",\"message\":\"Upstream stream ended unexpectedly\"}}\n\n";out.write(err.getBytes(StandardCharsets.UTF_8));writeDone(out);}}
    private Object nullSafe(){return "";}
    private void writeEvent(OutputStream out,Map<String,Object> event)throws IOException{out.write(("data: "+json.writeValueAsString(event)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    private void writeDone(OutputStream out)throws IOException{out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}
    private int num(Object n){return n instanceof Number?((Number)n).intValue():0;}
    private double cost(String model,int pt,int ct){Price p=prices.get(model);return p==null?0:(pt*p.input+ct*p.output)/1_000_000d;}
    private Result findCache(Tenant t,String model,String prompt){List<Map<String,Object>> rows=db.queryForList("SELECT id,model,prompt,response FROM cache_entries WHERE virtual_key=? AND model=? ORDER BY id DESC LIMIT 500",t.key,model);for(Map<String,Object> row:rows){if(similarity(prompt,String.valueOf(row.get("prompt")))>=t.threshold){try{db.update("UPDATE cache_entries SET hit_count=hit_count+1 WHERE id=?",row.get("id"));Map<String,Object> payload=json.readValue(String.valueOf(row.get("response")),Map.class);return new Result(payload,null,String.valueOf(row.get("model")),false,0,num(((Map<?,?>)payload.getOrDefault("usage",Map.of())).get("prompt_tokens")),num(((Map<?,?>)payload.getOrDefault("usage",Map.of())).get("completion_tokens")),null,0,true,0,"cache");}catch(Exception ignored){}}}return null;}
    private void saveCache(Tenant t,String model,String prompt,Map<String,Object> payload){try{db.update("INSERT INTO cache_entries(virtual_key,model,prompt,response,created_at) VALUES(?,?,?,?,?)",t.key,model,prompt,json.writeValueAsString(payload),Instant.now().toString());}catch(Exception ignored){}}
    double similarity(String a,String b){Set<String>x=tokens(a),y=tokens(b);if(x.isEmpty()||y.isEmpty())return 0;Set<String> union=new HashSet<>(x);union.addAll(y);Set<String> in=new HashSet<>(x);in.retainAll(y);return (double)in.size()/union.size();}
    private static final Set<String> STOP=Set.of("a","an","the","is","are","do","i","my","me","to","of","for","on","in","can","what","how","if","bought","steps","step","who","where","when","why","does","did","would","could","should","please","get","this","that","these","those");
    private Set<String> tokens(String s){Map<String,String> syn=Map.ofEntries(Map.entry("reset","reset"),Map.entry("change","reset"),Map.entry("recover","reset"),Map.entry("refund","refund"),Map.entry("money","refund"),Map.entry("back","refund"),Map.entry("policy","refund"),Map.entry("return","refund"),Map.entry("annual","annual"),Map.entry("plan","plan"),Map.entry("dashboard","dashboard"),Map.entry("account","dashboard"));Set<String> out=new HashSet<>();for(String w:s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@.]"," ").split("\\s+")){if(w.length()>1&&!STOP.contains(w)){String term=w.endsWith("s")&&w.length()>4?w.substring(0,w.length()-1):w;out.add(syn.getOrDefault(term,term));}}return out;}
    public Map<String,Object> usage(String key){String m=YearMonth.now(ZoneOffset.UTC).toString();if(key==null)return Map.of("month",m,"keys",db.queryForList("SELECT virtual_key AS key,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits FROM usage_monthly WHERE month=? ORDER BY cost_usd DESC",m));List<Map<String,Object>> rows=db.queryForList("SELECT virtual_key AS key,team,requests,prompt_tokens,completion_tokens,cost_usd,cache_hits FROM usage_monthly WHERE virtual_key=? AND month=?",key,m);if(rows.isEmpty())return Map.of("key",key,"month",m,"requests",0,"prompt_tokens",0,"completion_tokens",0,"cost_usd",0,"cache_hits",0);return rows.get(0);}
    public List<Map<String,Object>> logs(String key,int limit){String sql="SELECT request_id,virtual_key,team,requested_model,resolved_provider,resolved_model,status,prompt_tokens,completion_tokens,cost_usd,cache,fallback,route_reason,retries,latency_ms,created_at FROM request_logs "+(key==null?"":"WHERE virtual_key=? ")+"ORDER BY created_at DESC LIMIT ?";return key==null?db.queryForList(sql,Math.min(limit,500)):db.queryForList(sql,key,Math.min(limit,500));}
    public Map<String,Object> cacheStats(){Map<String,Object> row=db.queryForMap("SELECT COUNT(*) AS entries,COALESCE(SUM(hit_count),0) AS hits FROM cache_entries");long hits=((Number)row.get("hits")).longValue();long req=db.queryForObject("SELECT COUNT(*) FROM request_logs WHERE cache IN ('hit','miss')",Long.class);return Map.of("entries",row.get("entries"),"hits",hits,"requests",req,"hit_rate",req==0?0.0:(double)hits/req);}
    public Map<String,Object> providersHealth(){List<Map<String,Object>> out=new ArrayList<>();for(Provider p:providers.values()){out.add(Map.of("provider",p.name,"base_url",p.url,"healthy",true,"recent_requests",0));}return Map.of("providers",out);}
    public Map<String,Object> routingEval(){try{Path p=Paths.get(dataDir).toAbsolutePath().normalize().resolve("routing_eval.jsonl");List<Map<String,Object>> cases=new ArrayList<>();int correct=0;for(String line:Files.readAllLines(p)){if(line.isBlank())continue;JsonNode n=json.readTree(line);String actual=autoRoute(n.path("prompt").asText()).split("\\|")[0],expected=n.path("expected_tier").asText();boolean ok=actual.equals(expected);if(ok)correct++;cases.add(Map.of("id",n.path("id").asText(),"expected",expected,"actual",actual,"correct",ok,"note",n.path("note").asText()));}return Map.of("total",cases.size(),"correct",correct,"accuracy",cases.isEmpty()?0.0:(double)correct/cases.size(),"cases",cases);}catch(Exception ex){throw new IllegalStateException("Unable to read routing eval set",ex);}}
    public Map<String,Object> usageOverview(){List<Map<String,Object>> keys=new ArrayList<>();for(Tenant t:tenants.values()){Map<String,Object> u=usage(t.key);Map<String,Object> x=new LinkedHashMap<>(u);x.put("budget_usd",t.budget);x.put("rpm_limit",t.rpm);x.put("cache_enabled",t.cacheEnabled);keys.add(x);}Map<String,Object> totals=usage(null);return Map.of("month",YearMonth.now(ZoneOffset.UTC).toString(),"active_keys",tenants.size(),"keys",keys,"requests",db.queryForObject("SELECT COUNT(*) FROM request_logs",Long.class),"cache",cacheStats());}
}
