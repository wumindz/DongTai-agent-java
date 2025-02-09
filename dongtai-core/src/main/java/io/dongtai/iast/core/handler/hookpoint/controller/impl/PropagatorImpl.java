package io.dongtai.iast.core.handler.hookpoint.controller.impl;

import io.dongtai.iast.core.EngineManager;
import io.dongtai.iast.core.handler.hookpoint.models.*;
import io.dongtai.iast.core.handler.hookpoint.vulscan.taintrange.*;
import io.dongtai.iast.core.utils.StackUtils;
import io.dongtai.iast.core.utils.TaintPoolUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传播节点处理逻辑暂无问题，后续优先排查其他地方的问题
 *
 * @author dongzhiyong@huoxian.cn
 */
public class PropagatorImpl {
    private static final String PARAMS_OBJECT = "O";
    private static final String PARAMS_PARAM = "P";
    private static final String PARAMS_RETURN = "R";
    private static final String CONDITION_AND = "&";
    private static final String CONDITION_OR = "|";
    private static final String CONDITION_AND_RE_PATTERN = "[\\|&]";
    private static final int STACK_DEPTH = 11;

    private final static Set<String> SKIP_SCOPE_METHODS = new HashSet<String>(Arrays.asList(
            "java.net.URI.<init>(java.lang.String)",
            "java.net.URI.<init>(java.lang.String,java.lang.String,java.lang.String)",
            "java.net.URI.<init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)",
            "java.net.URI.<init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String)", // indirect
            "java.net.URI.<init>(java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String)",
            "java.net.URL.<init>(java.lang.String)", // indirect
            "java.net.URL.<init>(java.net.URL,java.lang.String)", // indirect
            "java.net.URL.<init>(java.net.URL,java.lang.String,java.net.URLStreamHandler)",
            "java.net.URL.<init>(java.lang.String,java.lang.String,java.lang.String)", // indirect
            "java.net.URL.<init>(java.lang.String,java.lang.String,int,java.lang.String)", // indirect
            "java.net.URL.<init>(java.lang.String,java.lang.String,int,java.lang.String,java.net.URLStreamHandler)"
    ));

    public static void solvePropagator(MethodEvent event, AtomicInteger invokeIdSequencer) {
        if (EngineManager.TAINT_HASH_CODES.isEmpty()) {
            return;
        }

        IastPropagatorModel propagator = IastHookRuleModel.getPropagatorByMethodSignature(event.signature);
        if (propagator != null) {
            auxiliaryPropagator(propagator, invokeIdSequencer, event);
        }
    }

    private static void addPropagator(IastPropagatorModel propagator, MethodEvent event, AtomicInteger invokeIdSequencer) {
        // skip same source and target
        if (event.getSourceHashes().size() == event.getTargetHashes().size()
                && event.getSourceHashes().equals(event.getTargetHashes())
                && propagator != null
                && !(PARAMS_OBJECT.equals(propagator.getSource()) && PARAMS_OBJECT.equals(propagator.getTarget()))) {
            return;
        }

        event.source = false;
        event.setCallStacks(StackUtils.createCallStack(6));
        int invokeId = invokeIdSequencer.getAndIncrement();
        event.setInvokeId(invokeId);
        EngineManager.TRACK_MAP.get().put(invokeId, event);
    }

    private static void auxiliaryPropagator(IastPropagatorModel propagator, AtomicInteger invokeIdSequencer, MethodEvent event) {
        String sourceString = propagator.getSource();
        boolean conditionSource = contains(sourceString);
        if (!conditionSource) {
            if (PARAMS_OBJECT.equals(sourceString)) {
                if (!TaintPoolUtils.isNotEmpty(event.object)
                        || !TaintPoolUtils.isAllowTaintType(event.object)
                        || !TaintPoolUtils.poolContains(event.object, event)) {
                    return;
                }

                event.setInValue(event.object);
                setTarget(propagator, event);
                addPropagator(propagator, event, invokeIdSequencer);
            } else if (sourceString.startsWith(PARAMS_PARAM)) {
                ArrayList<Object> inValues = new ArrayList<Object>();
                ArrayList<String> inValuesString = new ArrayList<String>();
                int[] positions = (int[]) propagator.getSourcePosition();
                for (int pos : positions) {
                    if (pos >= event.argumentArray.length) {
                        continue;
                    }

                    Object tempObj = event.argumentArray[pos];
                    if (!TaintPoolUtils.isNotEmpty(tempObj)
                            || !TaintPoolUtils.isAllowTaintType(tempObj)
                            || !TaintPoolUtils.poolContains(tempObj, event)) {
                        continue;
                    }
                    inValues.add(tempObj);
                    inValuesString.add(event.obj2String(tempObj));
                }
                if (!inValues.isEmpty()) {
                    event.setInValue(inValues.toArray(), inValuesString.toString());
                    setTarget(propagator, event);
                    addPropagator(propagator, event, invokeIdSequencer);
                }
            }
        } else {
            // o&r 解决
            // @TODO: R has been tainted, so we not need to propagate it
            boolean andCondition = sourceString.contains(CONDITION_AND);
            String[] conditionSources = sourceString.split(CONDITION_AND_RE_PATTERN);
            ArrayList<Object> inValues = new ArrayList<Object>();
            for (String source : conditionSources) {
                if (PARAMS_OBJECT.equals(source)) {
                    if (event.object == null) {
                        break;
                    }
                    inValues.add(event.object);
                } else if (PARAMS_RETURN.equals(source)) {
                    if (event.returnValue == null) {
                        break;
                    }
                    event.setInValue(event.returnValue);
                } else if (source.startsWith(PARAMS_PARAM)) {
                    int[] positions = (int[]) propagator.getSourcePosition();
                    for (int pos : positions) {
                        Object tempObj = event.argumentArray[pos];
                        if (tempObj != null) {
                            inValues.add(tempObj);
                        }
                    }
                }
            }
            if (!inValues.isEmpty()) {
                int condition = 0;
                for (Object obj : inValues) {
                    if (TaintPoolUtils.isNotEmpty(obj)
                            && TaintPoolUtils.isAllowTaintType(obj)
                            && TaintPoolUtils.poolContains(obj, event)) {
                        condition++;
                    }
                }
                if (condition > 0 && (!andCondition || conditionSources.length == condition)) {
                    event.setInValue(inValues.toArray());
                    setTarget(propagator, event);
                    addPropagator(propagator, event, invokeIdSequencer);
                }
            }
        }
    }

    private static void setTarget(IastPropagatorModel propagator, MethodEvent event) {
        String target = propagator.getTarget();
        if (PARAMS_OBJECT.equals(target)) {
            event.setOutValue(event.object);
            trackTaintRange(propagator, event);
        } else if (PARAMS_RETURN.equals(target)) {
            event.setOutValue(event.returnValue);
            trackTaintRange(propagator, event);
        } else if (target.startsWith(PARAMS_PARAM)) {
            ArrayList<Object> outValues = new ArrayList<Object>();
            Object tempPositions = propagator.getTargetPosition();
            int[] positions = (int[]) tempPositions;
            if (positions.length == 1) {
                event.setOutValue(event.argumentArray[positions[0]]);
                trackTaintRange(propagator, event);
            } else {
                for (int pos : positions) {
                    outValues.add(event.argumentArray[pos]);
                    trackTaintRange(propagator, event);
                }
                if (!outValues.isEmpty()) {
                    event.setOutValue(outValues.toArray());
                }
            }
        }

        EngineManager.TAINT_HASH_CODES.addObject(event.outValue, event, false);
    }

    private static TaintRanges getTaintRanges(Object obj) {
        int hash = System.identityHashCode(obj);
        TaintRanges tr = EngineManager.TAINT_RANGES_POOL.get(hash);
        if (tr == null) {
            tr = new TaintRanges();
        } else {
            tr = tr.clone();
        }
        return tr;
    }

    private static void trackTaintRange(IastPropagatorModel propagator, MethodEvent event) {
        TaintCommandRunner r = TaintCommandRunner.getCommandRunner(event.signature);

        TaintRanges oldTaintRanges = new TaintRanges();
        TaintRanges srcTaintRanges = new TaintRanges();

        Object src = null;
        if (r != null) {
            String srcLoc = propagator.getSource();
            if (PARAMS_OBJECT.equals(srcLoc)) {
                src = event.object;
                srcTaintRanges = getTaintRanges(src);
            } else if (srcLoc.startsWith("O|P")) {
                oldTaintRanges = getTaintRanges(event.object);
                int[] positions = (int[]) propagator.getSourcePosition();
                if (positions.length == 1 && event.argumentArray.length >= positions[0]) {
                    src = event.argumentArray[positions[0]];
                    srcTaintRanges = getTaintRanges(src);
                }
            } else if (srcLoc.startsWith(PARAMS_PARAM)) {
                // invalid policy
                if (srcLoc.contains(CONDITION_OR) || srcLoc.contains(CONDITION_AND)) {
                    return;
                }
                int[] positions = (int[]) propagator.getSourcePosition();
                if (positions.length == 1 && event.argumentArray.length >= positions[0]) {
                    src = event.argumentArray[positions[0]];
                    srcTaintRanges = getTaintRanges(src);
                }
            }
        }

        int tgtHash;
        Object tgt;
        String tgtLoc = propagator.getTarget();
        if (PARAMS_OBJECT.equals(tgtLoc)) {
            tgt = event.object;
            tgtHash = System.identityHashCode(tgt);
            oldTaintRanges = getTaintRanges(tgt);
        } else if (PARAMS_RETURN.equals(tgtLoc)) {
            tgt = event.returnValue;
            tgtHash = System.identityHashCode(tgt);
        } else if (tgtLoc.startsWith(PARAMS_PARAM)) {
            // invalid policy
            if (tgtLoc.contains(CONDITION_OR) || tgtLoc.contains(CONDITION_AND)) {
                return;
            }
            int[] positions = (int[]) propagator.getTargetPosition();
            if (positions.length != 1 || event.argumentArray.length < positions[0]) {
                // target can only have one parameter
                return;
            }
            tgt = event.argumentArray[positions[0]];
            tgtHash = System.identityHashCode(tgt);
            oldTaintRanges = getTaintRanges(tgt);
        } else {
            // invalid policy
            return;
        }

        if (!TaintPoolUtils.isNotEmpty(tgt)) {
            return;
        }

        TaintRanges tr;
        if (r != null && src != null) {
            tr = r.run(src, tgt, event.argumentArray, oldTaintRanges, srcTaintRanges);
        } else {
            tr = new TaintRanges(new TaintRange(0, TaintRangesBuilder.getLength(tgt)));
        }
        event.targetRanges.add(new MethodEvent.MethodEventTargetRange(tgtHash, tr));
        EngineManager.TAINT_RANGES_POOL.add(tgtHash, tr);
    }

    public static boolean isSkipScope(String signature) {
        return SKIP_SCOPE_METHODS.contains(signature);
    }

    private static boolean contains(String obj) {
        return obj.contains(CONDITION_AND) || obj.contains(CONDITION_OR);
    }
}
