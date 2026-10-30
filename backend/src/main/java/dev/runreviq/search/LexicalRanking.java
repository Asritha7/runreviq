package dev.runreviq.search;

import java.util.*;
import java.util.function.Consumer;
import static dev.runreviq.search.RetrievalTrace.*;
import java.util.regex.Pattern;

/** Port of the reviewed-v5 lexical rules. Scores are never answer confidence. */
public final class LexicalRanking {
    private LexicalRanking() {}
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.UNICODE_CHARACTER_CLASS;
    public static Pattern pattern(String regex){return Pattern.compile(regex,FLAGS);}
    public static boolean contains(String regex,String text){return pattern(regex).matcher(text).find();}
    private static final Set<String> SCAFFOLD=Set.of(("ask can could would should what which how why when where please tell show give use need help check inspect compare find look metrics "
        +"runbook runbooks documentation procedure procedures instructions tells another recommends checking one older latest approved revision follow considering upgrading users receiving some does did about recent conclude definitely current right now applies apply").split(" "));
    private static final String EXCLUSION="\\b(?:does not|do not) (?:cover|specify|authorize|establish)\\b|\\boutside (?:this|the) (?:procedure|scope)\\b|\\bnot instructions for\\b";
    public static String normalize(String text){
        text=pattern("\\bdatabase slots?\\b").matcher(text).replaceAll("database connection");
        text=pattern("\\beligible to receive traffic\\b").matcher(text).replaceAll("ready");
        text=pattern("\\b(HTTP\\s+[45][0-9]{2})\\s+responses?\\b").matcher(text).replaceAll("$1 failures");
        text=pattern("\\btoken validation\\b").matcher(text).replaceAll("authentication validation");
        var aliases=Map.ofEntries(Map.entry("errors","failures"),Map.entry("error","failure"),Map.entry("recovery","recover"),Map.entry("verification","verify"),Map.entry("validation","verify"),Map.entry("validate","verify"),Map.entry("instances","replicas"),Map.entry("instance","replica"),Map.entry("jobs","tasks"),Map.entry("job","task"),Map.entry("requeue","retry"),Map.entry("requeued","retry"),Map.entry("requeuing","retry"));
        return pattern("\\b(?:"+String.join("|",aliases.keySet())+")\\b").matcher(text).replaceAll(m->aliases.get(m.group().toLowerCase(Locale.ROOT)));
    }
    public static List<String> exactTerms(String text){
        return Pattern.compile("(?<![\\w.-])(?:[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)+|[0-9]+(?:\\.[0-9]+)*)(?![\\w-]|\\.\\d)",Pattern.UNICODE_CHARACTER_CLASS).matcher(text).results().map(m->m.group()).toList();
    }
    public static boolean hasExact(String text,Collection<String> terms){return terms.stream().allMatch(t->contains("(?<![\\w.-])"+Pattern.quote(t)+"(?![\\w-]|\\.\\d)",text));}
    public static Set<String> httpTerms(String text){
        if(!contains("\\b(?:http|status|responses?|errors?)\\b",text))return Set.of();
        var result=new HashSet<String>();for(String t:exactTerms(text))if(t.matches("[1-5][0-9]{2}"))result.add(t);return result;
    }
    private static Set<String> groups(String regex,String text){var result=new HashSet<String>();var m=pattern(regex).matcher(text);while(m.find())result.add(m.group(1));return result;}
    public static boolean numericMatch(String text,List<String> required,Set<String> codes){
        if(!hasExact(text,required.stream().filter(t->!codes.contains(t)).toList()))return false;
        if(codes.isEmpty())return true;
        var restricted=groups("\\b(?:only(?:\\s+for)?|restricted\\s+to)\\s+(?:HTTP\\s+)?([1-5][0-9]{2})\\b",text);
        restricted.addAll(groups("\\bHTTP\\s+([1-5][0-9]{2})(?:\\s+(?:errors?|responses?|incidents?))?\\s+only\\b",text));
        var excluded=groups("\\bnot\\s+(?:for\\s+)?(?:HTTP\\s+)?([1-5][0-9]{2})\\b",text);
        if(!Collections.disjoint(codes,excluded)||(!restricted.isEmpty()&&!restricted.containsAll(codes)))return false;
        return hasExact(text,codes)||exactTerms(text).stream().noneMatch(t->t.matches("[1-5][0-9]{2}"));
    }
    public static String searchable(String text){return String.join(" ",Arrays.stream(pattern("(?<=[.!?])\\s+").split(text)).filter(s->!contains(EXCLUSION,s)).toList());}
    public static String query(String text){return String.join(" ",pattern("[\\w.-]+").matcher(text).results().map(m->m.group()).filter(w->!SCAFFOLD.contains(w.toLowerCase(Locale.ROOT))).toList());}
    public static Set<String> intersection(Collection<String> a,Collection<String> b){var r=new HashSet<>(a);r.retainAll(b);return r;}
    private static double weight(Collection<String> terms,Map<String,Double> weights){double sum=0;for(String t:new TreeSet<>(terms))sum+=weights.get(t);return sum;}
    private record Scored(double score,int index,Map<String,Object> passage,boolean intentMatch){}
    public static List<Map<String,Object>> rank(List<Map<String,Object>> candidates,List<String> queryTerms,List<List<String>> bodies,List<List<String>> contexts,List<List<String>> policies,double floor,List<List<String>> groups,int limit){
        return rank(candidates,queryTerms,bodies,contexts,policies,floor,groups,limit,null,List.of());
    }
    /** Optional precision candidate. Eligibility and citation construction remain outside ranking. */
    public static List<Map<String,Object>> rank(List<Map<String,Object>> candidates,List<String> queryTerms,List<List<String>> bodies,List<List<String>> contexts,List<List<String>> policies,double floor,List<List<String>> groups,int limit,String question,List<String> headings){
        return rank(candidates,queryTerms,bodies,contexts,policies,floor,groups,limit,question,headings,null);
    }
    public static List<Map<String,Object>> rank(List<Map<String,Object>> candidates,List<String> queryTerms,List<List<String>> bodies,List<List<String>> contexts,List<List<String>> policies,double floor,List<List<String>> groups,int limit,String question,List<String> headings,Consumer<Map<String,Object>> trace){
        if(trace!=null)emit(trace,"ranking_input","query_terms",queryTerms,"floor",floor,"body_floor",.15,"limit",limit,"clause_groups",groups,"focused",question!=null,"relative_floor",question!=null?.65:.40,"novelty_floor",question!=null?.45:.30,"candidates",candidates.stream().map(RetrievalTrace::key).toList());
        boolean focused=question!=null;
        var query=new TreeSet<>(queryTerms);if(query.isEmpty()||candidates.isEmpty())return List.of();
        var frequency=new HashMap<String,Integer>();var documentTerms=new HashMap<String,Set<String>>();
        var counts=new ArrayList<Map<String,Integer>>();double average=0;
        for(int i=0;i<candidates.size();i++){
            var count=new HashMap<String,Integer>();for(String t:bodies.get(i))count.merge(t,1,Integer::sum);counts.add(count);average+=bodies.get(i).size();
            var terms=new HashSet<>(bodies.get(i));terms.addAll(contexts.get(i));for(String t:terms)frequency.merge(t,1,Integer::sum);
            documentTerms.computeIfAbsent((String)candidates.get(i).get("document_id"),k->new HashSet<>()).addAll(terms);
        }
        average/=candidates.size();if(average==0)average=1;
        var weights=new HashMap<String,Double>();for(String t:query)weights.put(t,Math.log(1+(candidates.size()+1.0)/(frequency.getOrDefault(t,0)+1)));
        double total=weight(query,weights);var scored=new ArrayList<Scored>();
        var anchors=new HashSet<String>();for(var terms:contexts)anchors.addAll(terms);anchors.retainAll(query);
        anchors.removeAll(Set.of("verifi","recover","approv","request","incident","failur","error","check","work","confirm","investig","respons","chang"));
        boolean verification=focused&&((contains("\\b(?:verify|validate|confirm|checks?)\\b",question)&&contains("\\b(?:recovery|working|healthy|success|recovered)\\b",question))||contains("\\bwhen\\b.*\\bresume\\b",question)||contains("\\b(?:observations|indicators|signs|measurements)\\b.*\\b(?:show|indicate|demonstrate|confirm)\\b",question));
        boolean investigation=focused&&!verification&&contains("\\b(?:investigate|inspect|compare|distinguish|diagnose|check)\\b",question);
        for(int i=0;i<candidates.size();i++){
            var body=counts.get(i);var matched=intersection(query,body.keySet());var terms=documentTerms.get((String)candidates.get(i).get("document_id"));
            double coverage=weight(intersection(query,terms),weights)/total;
            for(var group:groups){var local=intersection(query,group);if(intersection(local,body.keySet()).size()>=2)coverage=Math.max(coverage,weight(intersection(local,terms),weights)/weight(local,weights));}
            if(trace!=null)emit(trace,"coverage","passage",key(candidates.get(i)),"query_weights",weights,"matched_body_terms",matched,"body_terms",body.keySet(),"document_terms",terms,"coverage",coverage,"body_coverage",weight(matched,weights)/total,"floor",floor,"body_floor",.15,
                "decision",coverage<floor?"document_coverage":weight(matched,weights)/total<.15?"body_coverage":"eligible");
            if(coverage<floor||weight(matched,weights)/total<.15)continue;
            double score=0;for(String t:new TreeSet<>(matched))score+=weights.get(t)*body.get(t)*2.2/(body.get(t)+1.2*(.25+.75*bodies.get(i).size()/average));
            score+=.60*weight(intersection(query,contexts.get(i)),weights);if(intersection(query,policies.get(i)).size()>=2)score*=1.25;
            boolean intentMatch=false;
            if(focused){
                // Require passage evidence as before; title/heading support only adjusts relevance.
                double bodyCoverage=weight(matched,weights)/total;
                var local=new HashSet<>(body.keySet());local.addAll(contexts.get(i));
                double anchorCoverage=anchors.isEmpty()?1:weight(intersection(anchors,local),weights)/weight(anchors,weights);
                score*= (.5+.5*bodyCoverage)*(.5+anchorCoverage);
                String heading=headings.get(i);
                intentMatch=(verification&&contains("\\b(?:verification|validation|recovery|success)\\b",heading))
                    ||(investigation&&contains("\\b(?:investigation|diagnostics?|troubleshooting)\\b",heading));
                if(intentMatch)score*=2;
                intentMatch=intentMatch&&anchorCoverage==1;
            }
            if(trace!=null)emit(trace,"score","passage",key(candidates.get(i)),"score",score*coverage,"intent_match",intentMatch);
            scored.add(new Scored(score*coverage,i,candidates.get(i),intentMatch));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        if(scored.isEmpty())return List.of();var selected=new ArrayList<Map<String,Object>>();var covered=new HashSet<String>();var intentDocuments=new HashSet<Object>();
        int processed=0;
        for(var s:scored){processed++;var matched=intersection(query,counts.get(s.index).keySet());var fresh=new HashSet<>(matched);fresh.removeAll(covered);
            if(s.score>=scored.getFirst().score*(focused?.65:.40)||(s.score>=scored.getFirst().score*(focused?.45:.30)&&!fresh.isEmpty())||(focused&&s.intentMatch&&!intentDocuments.contains(s.passage.get("document_id"))&&s.score>=scored.getFirst().score*.30)){selected.add(s.passage);covered.addAll(matched);if(s.intentMatch)intentDocuments.add(s.passage.get("document_id"));}if(selected.size()==limit)break;
        }
        if(trace!=null)for(int i=0;i<scored.size();i++){var s=scored.get(i);emit(trace,"selection","passage",key(s.passage),"rank",i+1,"score",s.score,"best_score",scored.getFirst().score,"limit",limit,"decision",selected.contains(s.passage)?"selected":i>=processed?"top_k":"relative_score");}
        return selected;
    }
}
