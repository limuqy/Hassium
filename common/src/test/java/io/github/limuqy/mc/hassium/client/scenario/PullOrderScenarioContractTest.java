package io.github.limuqy.mc.hassium.client.scenario;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Parse the actual shipped scripts: a stage assertion must never precede its trigger/await. */
class PullOrderScenarioContractTest {
    private static final List<String> CASES=List.of("pullorder","pullorderlegacy","pullordersame","pullorderoff","pullorderfail","pullorderhalo");
    private static List<ScenarioStep> load(String name) throws Exception {
        try(var stream=PullOrderScenarioContractTest.class.getResourceAsStream("/hassium/smoke/scenario/"+name+".scenario")) {
            assertNotNull(stream,name);
            return ScenarioStep.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList(),Map.of());
        }
    }
    private static void verify(String name,List<ScenarioStep> steps) {
        boolean sleeps=!name.equals("pullorderoff") && !name.equals("pullorderfail");
        boolean built=false,entered=false,woke=false,recovered=false,quiescent=false,haloArmed=false,haloDone=false;
        Set<Integer> requests=new HashSet<>(),chains=new HashSet<>();
        for(var step:steps) {
            if(step.type()==ScenarioStep.Type.COMMAND) {
                assertFalse(step.param("text").equals("setblock 52 140 6 redstone_block"),"Native packet shape cannot trigger a request");
                assertFalse(step.param("text").equals("setblock 68 140 6 redstone_block"),"Native packet shape cannot trigger a request");
            } else if(step.type()==ScenarioStep.Type.SLEEP) {
                if("enter".equals(step.param("action"))) { assertTrue(built); entered=true; }
                if("wake".equals(step.param("action"))) { assertTrue(entered); woke=true; }
            } else if(step.type()==ScenarioStep.Type.PULL_ORDER) {
                int x=(int)step.longParam("x",3);
                switch(step.param("action")) {
                    case "b" -> { assertFalse(entered); assertTrue(step.longParam("timeoutMs",0)>0); built=true; }
                    case "ready" -> {
                        assertTrue(sleeps && entered && woke,"COLUMN_READY wait follows sleep wake");
                        assertTrue(step.longParam("timeoutMs",0)>30_000,"Allow actual recovery; never assume a fixed 20-second sleep");
                        recovered=true;
                    }
                    case "request" -> {
                        assertTrue(built && (!sleeps || recovered),"Request must follow actual B/recovery stages");
                        if(name.equals("pullorderhalo")) assertTrue(haloArmed);
                        assertTrue(requests.add(x),"One deliberate request per column");
                    }
                    case "chain" -> {
                        assertTrue(requests.contains(x),"Await must follow this column's request");
                        assertTrue(step.longParam("timeoutMs",0)>0 && step.longParam("timeoutMs",0)<30_000,
                                "Completion must precede production timeout repair");
                        assertTrue(chains.add(x));
                    }
                    case "quiescent" -> {
                        if(name.equals("pullorderhalo")) assertTrue(haloDone,"Halo phase must precede the global resource gate");
                        assertEquals(requests,chains,"Observe resources only after every deliberate result");
                        assertTrue(step.longParam("timeoutMs",0)>0 && step.longParam("timeoutMs",0)<=15000);
                        quiescent=true;
                    }
                    case "haloArm" -> {
                        assertEquals("pullorderhalo",name);assertTrue(built && recovered && requests.isEmpty());
                        assertTrue(step.longParam("timeoutMs",0)>0 && step.longParam("timeoutMs",0)<=15000);haloArmed=true;
                    }
                    case "halo" -> {
                        assertEquals("pullorderhalo",name);assertTrue(haloArmed);assertEquals(Set.of(3,4),chains);
                        assertTrue(step.longParam("timeoutMs",0)>0 && step.longParam("timeoutMs",0)<=15000);haloDone=true;
                    }
                    default -> fail("Unknown ordering fixture action");
                }
            } else if(step.type()==ScenarioStep.Type.ASSERT_PROBE) {
                String key=step.param("key");
                if(key.equals("pullOrder.officialExerciseLanded")) assertTrue(built);
                if(key.equals("pullOrder.haloPhases")) assertTrue(haloDone);
                if(Set.of("pullOrder.inflight","pullOrder.targetInflight","pullOrder.reservedBytes").contains(key))
                    assertTrue(quiescent,"Resource zero is observed after a bounded stable wait");
                if(Set.of("pullOrder.responses","pullOrder.afterReceipts","pullOrder.applications","pullOrder.checks","pullOrder.passes").contains(key))
                    assertTrue(chains.size()>=step.longParam("value",0),"Stage assertion must follow final async result await: "+step);
            }
        }
        Set<Integer> expected=name.equals("pullorder") || name.equals("pullorderlegacy") || name.equals("pullorderhalo")?Set.of(3,4):Set.of(3);
        assertEquals(expected,requests); assertEquals(expected,chains);
        assertEquals(sleeps,recovered);
        assertTrue(quiescent);
        assertEquals(name.equals("pullorderhalo"),haloArmed);assertEquals(haloArmed,haloDone);
    }
    @Test void allSixShippedScenariosAwaitRealRecoveryRequestAndFinalResultsInOrder() throws Exception {
        for(String name:CASES) verify(name,load(name));
    }
    @Test void missingRequestOrRecoveryWaitFailsThePreflightBeforeAnyGameStarts() throws Exception {
        var steps=load("pullorderlegacy");
        assertThrows(AssertionError.class,()->verify("pullorderlegacy",steps.stream().filter(s->
                s.type()!=ScenarioStep.Type.PULL_ORDER || !"request".equals(s.param("action"))).toList()));
        assertThrows(AssertionError.class,()->verify("pullorderlegacy",steps.stream().filter(s->
                s.type()!=ScenarioStep.Type.PULL_ORDER || !"ready".equals(s.param("action"))).toList()));
    }
    @Test void removingTheBoundedGlobalResourceWaitIsRejected() throws Exception {
        var steps=load("pullordersame");
        assertThrows(AssertionError.class,()->verify("pullordersame",steps.stream().filter(s->
                s.type()!=ScenarioStep.Type.PULL_ORDER || !"quiescent".equals(s.param("action"))).toList()));
    }
    @Test void missingHaloArmingOrActualPhaseWaitIsRejectedBeforeLaunchingTheGame() throws Exception {
        var steps=load("pullorderhalo");
        for(String action:List.of("haloArm","halo")) assertThrows(AssertionError.class,()->verify("pullorderhalo",steps.stream().filter(s->
                s.type()!=ScenarioStep.Type.PULL_ORDER || !action.equals(s.param("action"))).toList()));
    }
}
