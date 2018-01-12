// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.planner;

import java.util.ArrayList;
import java.util.List;

import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.Expr;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.analysis.TupleId;
import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.SlotRef;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TExpr;
import org.apache.impala.thrift.TPlanNode;
import org.apache.impala.thrift.TPlanNodeType;
import org.apache.impala.thrift.TQueryOptions;
import org.apache.impala.thrift.TUnionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Node that merges the results of its child plans, Normally, this is done by
 * materializing the corresponding result exprs into a new tuple. However, if
 * a child has an identical tuple layout as the output of the union node, and
 * the child only has naked SlotRefs as result exprs, then the child is marked
 * as 'passthrough'. The rows of passthrough children are directly returned by
 * the union node, instead of materializing the child's result exprs into new
 * tuples.
 */
public class UnionNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(UnionNode.class);

  // List of union result exprs of the originating UnionStmt. Used for
  // determining passthrough-compatibility of children.
  protected List<Expr> unionResultExprs_;

  // Expr lists corresponding to the input query stmts.
  // The ith resultExprList belongs to the ith child.
  // All exprs are resolved to base tables.
  protected List<List<Expr>> resultExprLists_ = Lists.newArrayList();

  // Expr lists that originate from constant select stmts.
  // We keep them separate from the regular expr lists to avoid null children.
  protected List<List<Expr>> constExprLists_ = Lists.newArrayList();

  // Materialized result/const exprs corresponding to materialized slots.
  // Set in init() and substituted against the corresponding child's output smap.
  protected List<List<Expr>> materializedResultExprLists_ = Lists.newArrayList();
  protected List<List<Expr>> materializedConstExprLists_ = Lists.newArrayList();

  // Indicates if this UnionNode is inside a subplan.
  protected boolean isInSubplan_;

  // Index of the first non-passthrough child.
  protected int firstMaterializedChildIdx_;

  protected final TupleId tupleId_;

  protected UnionNode(PlanNodeId id, TupleId tupleId) {
    super(id, tupleId.asList(), "UNION");
    unionResultExprs_ = Lists.newArrayList();
    tupleId_ = tupleId;
    isInSubplan_ = false;
  }

  protected UnionNode(PlanNodeId id, TupleId tupleId,
        List<Expr> unionResultExprs, boolean isInSubplan) {
    super(id, tupleId.asList(), "UNION");
    unionResultExprs_ = unionResultExprs;
    tupleId_ = tupleId;
    isInSubplan_ = isInSubplan;
  }

  public void addConstExprList(List<Expr> exprs) { constExprLists_.add(exprs); }

  /**
   * Returns true if this UnionNode has only constant exprs.
   */
  public boolean isConstantUnion() { return resultExprLists_.isEmpty(); }

  /**
   * Add a child tree plus its corresponding unresolved resultExprs.
   */
  public void addChild(PlanNode node, List<Expr> resultExprs) {
    super.addChild(node);
    resultExprLists_.add(resultExprs);
  }

  @Override
  public void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    cardinality_ = constExprLists_.size();
    for (PlanNode child: children_) {
      // ignore missing child cardinality info in the hope it won't matter enough
      // to change the planning outcome
      if (child.cardinality_ > 0) {
        cardinality_ = checkedAdd(cardinality_, child.cardinality_);
      }
      numNodes_ = Math.max(child.getNumNodes(), numNodes_);
    }
    // The number of nodes of a union node is -1 (invalid) if all the referenced tables
    // are inline views (e.g. select 1 FROM (VALUES(1 x, 1 y)) a FULL OUTER JOIN
    // (VALUES(1 x, 1 y)) b ON (a.x = b.y)). We need to set the correct value.
    if (numNodes_ == -1) numNodes_ = 1;
    cardinality_ = capAtLimit(cardinality_);
    if (LOG.isTraceEnabled()) {
      LOG.trace("stats Union: cardinality=" + Long.toString(cardinality_));
    }
  }

  @Override
  public void computeNodeResourceProfile(TQueryOptions queryOptions) {
    // TODO: add an estimate
    nodeResourceProfile_ = ResourceProfile.noReservation(0);
  }

  @Override
  public ExecPhaseResourceProfiles computeTreeResourceProfiles(
      TQueryOptions queryOptions) {
    // The union executes concurrently with Open() and GetNext() on each of it's
    // children.
    ResourceProfile maxProfile = ResourceProfile.invalid();
    for (PlanNode child : children_) {
      // Children are opened either during Open() or GetNext() of the union.
      ExecPhaseResourceProfiles childResources =
          child.computeTreeResourceProfiles(queryOptions);
      maxProfile = maxProfile.max(childResources.duringOpenProfile);
      maxProfile = maxProfile.max(childResources.postOpenProfile);
    }
    ResourceProfile peakResources = nodeResourceProfile_.sum(maxProfile);
    return new ExecPhaseResourceProfiles(peakResources, peakResources);
  }

  /**
   * Returns true if rows from the child with 'childTupleIds' and 'childResultExprs' can
   * be returned directly by the union node (without materialization into a new tuple).
   */
  private boolean isChildPassthrough(
      Analyzer analyzer, PlanNode childNode, List<Expr> childExprList) {
    List<TupleId> childTupleIds = childNode.getTupleIds();
    // Check that if the child outputs a single tuple, then it's not nullable. Tuple
    // nullability can be considered to be part of the physical row layout.
    Preconditions.checkState(childTupleIds.size() != 1 ||
        !childNode.getNullableTupleIds().contains(childTupleIds.get(0)));
    // If the Union node is inside a subplan, passthrough should be disabled to avoid
    // performance issues by forcing tiny batches.
    // TODO: Remove this as part of IMPALA-4179.
    if (isInSubplan_) return false;
    // Pass through is only done for the simple case where the row has a single tuple. One
    // of the motivations for this is that the output of a UnionNode is a row with a
    // single tuple.
    if (childTupleIds.size() != 1) return false;
    Preconditions.checkState(!unionResultExprs_.isEmpty());

    TupleDescriptor unionTupleDescriptor = analyzer.getDescTbl().getTupleDesc(tupleId_);
    TupleDescriptor childTupleDescriptor =
        analyzer.getDescTbl().getTupleDesc(childTupleIds.get(0));

    // Verify that the union tuple descriptor has one slot for every expression.
    Preconditions.checkState(
        unionTupleDescriptor.getSlots().size() == unionResultExprs_.size());
    // Verify that the union node has one slot for every child expression.
    Preconditions.checkState(
        unionTupleDescriptor.getSlots().size() == childExprList.size());

    if (unionResultExprs_.size() != childTupleDescriptor.getSlots().size()) return false;
    if (unionTupleDescriptor.getByteSize() != childTupleDescriptor.getByteSize()) {
      return false;
    }

    for (int i = 0; i < unionResultExprs_.size(); ++i) {
      if (!unionTupleDescriptor.getSlots().get(i).isMaterialized()) continue;
      SlotRef unionSlotRef = unionResultExprs_.get(i).unwrapSlotRef(false);
      SlotRef childSlotRef = childExprList.get(i).unwrapSlotRef(false);
      Preconditions.checkNotNull(unionSlotRef);
      if (childSlotRef == null) return false;
      if (!childSlotRef.getDesc().LayoutEquals(unionSlotRef.getDesc())) return false;
    }
    return true;
  }

  /**
   * Compute which children are passthrough and reorder them such that the passthrough
   * children come before the children that need to be materialized. Also reorder
   * 'resultExprLists_'. The children are reordered to simplify the implementation in the
   * BE.
   */
   void computePassthrough(Analyzer analyzer) {
    List<List<Expr>> newResultExprLists = Lists.newArrayList();
    ArrayList<PlanNode> newChildren = Lists.newArrayList();
    for (int i = 0; i < children_.size(); i++) {
      if (isChildPassthrough(analyzer, children_.get(i), resultExprLists_.get(i))) {
        newResultExprLists.add(resultExprLists_.get(i));
        newChildren.add(children_.get(i));
      }
    }
    firstMaterializedChildIdx_ = newChildren.size();

    for (int i = 0; i < children_.size(); i++) {
      if (!isChildPassthrough(analyzer, children_.get(i), resultExprLists_.get(i))) {
        newResultExprLists.add(resultExprLists_.get(i));
        newChildren.add(children_.get(i));
      }
    }

    Preconditions.checkState(resultExprLists_.size() == newResultExprLists.size());
    resultExprLists_ = newResultExprLists;
    Preconditions.checkState(children_.size() == newChildren.size());
    children_ = newChildren;
  }

  /**
   * Must be called after addChild()/addConstExprList(). Computes the materialized
   * result/const expr lists based on the materialized slots of this UnionNode's
   * produced tuple. The UnionNode doesn't need an smap: like a ScanNode, it
   * materializes an original tuple.
   * There is no need to call assignConjuncts() because all non-constant conjuncts
   * have already been assigned to the union operands, and all constant conjuncts have
   * been evaluated during registration to set analyzer.hasEmptyResultSet_.
   */
  @Override
  public void init(Analyzer analyzer) {
    Preconditions.checkState(conjuncts_.isEmpty());
    computeMemLayout(analyzer);
    computeStats(analyzer);
    computePassthrough(analyzer);

    // drop resultExprs/constExprs that aren't getting materialized (= where the
    // corresponding output slot isn't being materialized)
    materializedResultExprLists_.clear();
    Preconditions.checkState(resultExprLists_.size() == children_.size());
    List<SlotDescriptor> slots = analyzer.getDescTbl().getTupleDesc(tupleId_).getSlots();
    for (int i = 0; i < resultExprLists_.size(); ++i) {
      List<Expr> exprList = resultExprLists_.get(i);
      List<Expr> newExprList = Lists.newArrayList();
      Preconditions.checkState(exprList.size() == slots.size());
      for (int j = 0; j < exprList.size(); ++j) {
        if (slots.get(j).isMaterialized()) newExprList.add(exprList.get(j));
      }
      materializedResultExprLists_.add(
          Expr.substituteList(newExprList, getChild(i).getOutputSmap(), analyzer, true));
    }
    Preconditions.checkState(
        materializedResultExprLists_.size() == getChildren().size());

    materializedConstExprLists_.clear();
    for (List<Expr> exprList: constExprLists_) {
      Preconditions.checkState(exprList.size() == slots.size());
      List<Expr> newExprList = Lists.newArrayList();
      for (int i = 0; i < exprList.size(); ++i) {
        if (slots.get(i).isMaterialized()) newExprList.add(exprList.get(i));
      }
      materializedConstExprLists_.add(newExprList);
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    Preconditions.checkState(materializedResultExprLists_.size() == children_.size());
    List<List<TExpr>> texprLists = Lists.newArrayList();
    for (List<Expr> exprList: materializedResultExprLists_) {
      texprLists.add(Expr.treesToThrift(exprList));
    }
    List<List<TExpr>> constTexprLists = Lists.newArrayList();
    for (List<Expr> constTexprList: materializedConstExprLists_) {
      constTexprLists.add(Expr.treesToThrift(constTexprList));
    }
    Preconditions.checkState(firstMaterializedChildIdx_ <= children_.size());
    msg.union_node = new TUnionNode(
        tupleId_.asInt(), texprLists, constTexprLists, firstMaterializedChildIdx_);
    msg.node_type = TPlanNodeType.UNION_NODE;
  }

  @Override
  protected String getNodeExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(String.format("%s%s:%s\n", prefix, id_.toString(), displayName_));
    // A UnionNode may have predicates if a union is used inside an inline view,
    // and the enclosing select stmt has predicates referring to the inline view.
    if (!conjuncts_.isEmpty()) {
      output.append(detailPrefix + "predicates: " + getExplainString(conjuncts_) + "\n");
    }
    if (!constExprLists_.isEmpty()) {
      output.append(detailPrefix + "constant-operands=" + constExprLists_.size() + "\n");
    }
    if (detailLevel.ordinal() > TExplainLevel.MINIMAL.ordinal()) {
      List<String> passThroughNodeIds = Lists.newArrayList();
      for (int i = 0; i < firstMaterializedChildIdx_; ++i) {
        passThroughNodeIds.add(children_.get(i).getId().toString());
      }
      if (!passThroughNodeIds.isEmpty()) {
        String result = detailPrefix + "pass-through-operands: ";
        if (passThroughNodeIds.size() == children_.size()) {
          output.append(result + "all\n");
        } else {
          output.append(result + Joiner.on(",").join(passThroughNodeIds) + "\n");
        }
      }
    }
    return output.toString();
  }
}
