/*
 * MIT License
 *
 * Copyright (c) 2018-2020 Ctrip.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.ctrip.ferriswheel.core.dom.helper;

import com.ctrip.ferriswheel.core.dom.AttributeSnapshot;
import com.ctrip.ferriswheel.core.dom.ElementSnapshot;
import com.ctrip.ferriswheel.core.dom.NodeSnapshot;
import com.ctrip.ferriswheel.core.dom.TextNodeSnapshot;
import com.ctrip.ferriswheel.core.dom.diff.*;
import com.ctrip.ferriswheel.core.dom.impl.AttributeSnapshotImpl;
import com.ctrip.ferriswheel.core.dom.impl.ElementSnapshotImpl;
import com.ctrip.ferriswheel.core.dom.impl.TextNodeSnapshotImpl;
import com.ctrip.ferriswheel.core.util.ShiftAndReduceStack;

import java.util.*;

public class PatchHelper {

    public ElementSnapshot applyPatch(ElementSnapshot tree, Patch patch) {
        if (tree == null || patch == null) {
            throw new IllegalArgumentException();
        }

        if (patch.getDiffList().isEmpty()) {
            return tree;
        }

        TreeSet<NodeLocation> deletions = new TreeSet<>();
        TreeMap<NodeLocation, Diff> insertions = new TreeMap<>();
        analysePatch(patch, deletions, insertions);
        NavigableMap<NodeLocation, NodeSnapshot> tempNodes = applyDeletions(tree, deletions);
        tempNodes = applyStaticPatch(tempNodes, patch.getDiffList());
        return applyInsertions(tree, tempNodes, insertions);
    }

    void analysePatch(Patch patch,
                      TreeSet<NodeLocation> deletions,
                      TreeMap<NodeLocation, Diff> insertions) {
        for (Diff diff : patch.getDiffList()) {
            if (diff.getNegativeLocation() != null) {
                deletions.add(diff.getNegativeLocation());
            }
            if (diff.getPositiveLocation() != null) {
                Diff priorDiff = insertions.get(diff.getPositiveLocation());
                if (priorDiff == null || !priorDiff.hasContent()) {
                    insertions.put(diff.getPositiveLocation(), diff);
                }
            }
        }
    }

    NavigableMap<NodeLocation, NodeSnapshot> applyDeletions(ElementSnapshot negativeTree,
                                                            NavigableSet<NodeLocation> deletions) {
        NavigableMap<NodeLocation, NodeSnapshot> dirtyNodes = new TreeMap<>();
        TreeDeleteStack treeDeleteStack = new TreeDeleteStack(negativeTree, deletions, dirtyNodes);
        for (NodeLocation deleteLocation : deletions) {
            treeDeleteStack.feed(deleteLocation);
        }
        treeDeleteStack.terminate();
        return dirtyNodes;
    }

    /**
     * Patch nodes without dealing with there structure, and convert negative
     * temporary node map to positive temporary node map.
     *
     * @param negativeTempNodes
     * @param diffList
     * @return
     */
    NavigableMap<NodeLocation, NodeSnapshot> applyStaticPatch(NavigableMap<NodeLocation, NodeSnapshot> negativeTempNodes,
                                                              List<Diff> diffList) {
        NavigableMap<NodeLocation, NodeSnapshot> newTempNodes = new TreeMap<>();

        for (Diff diff : diffList) {
            if (diff.getPositiveLocation() == null) {
                continue;
            }

            NodeSnapshot negativeNode = null;
            boolean reservePreviousRef = false;
            if (diff.getNegativeLocation() != null) {
                negativeNode = negativeTempNodes.get(diff.getNegativeLocation());
                if (negativeNode == null) {
                    throw new IllegalStateException();
                }
            }

            // FIXME reservePreviousRef
            NodeSnapshot positiveNode = applyNodePatch(negativeNode, diff, reservePreviousRef);
            if (positiveNode == null) {
                throw new IllegalStateException();
            }
            newTempNodes.put(diff.getPositiveLocation(), positiveNode);
        }

        if (!newTempNodes.containsKey(NodeLocation.root()) && !diffList.isEmpty()) {
            NodeSnapshot root = negativeTempNodes.get(NodeLocation.root());
            if (root == null) {
                throw new IllegalStateException();
            }
            newTempNodes.put(NodeLocation.root(), root);
        }

        return newTempNodes;
    }

    ElementSnapshot applyInsertions(ElementSnapshot negativeTree,
                                    NavigableMap<NodeLocation, NodeSnapshot> positiveTempNodes,
                                    NavigableMap<NodeLocation, Diff> insertions) {
        TreeInsertStack treeInsertStack = new TreeInsertStack(negativeTree,
                positiveTempNodes,
                insertions);
        for (NodeLocation insertLocation : insertions.keySet()) {
            treeInsertStack.feed(insertLocation);
        }
        treeInsertStack.terminate();

        ElementSnapshot newTree = (ElementSnapshot) positiveTempNodes.get(NodeLocation.root());
        if (newTree == null) {
            newTree = (ElementSnapshot) positiveTempNodes.get(NodeLocation.root());
        }
        if (newTree == null) {
            newTree = negativeTree;
        }
        return newTree;
    }

    /**
     * Apply patch to a node, and return new patched node.
     *
     * @param negativeNode
     * @param diff
     * @param reservePreviousRef
     * @return A new patched node object, with previous node reference refer to
     * the specified <code>negativeNode</code>
     */
    NodeSnapshot applyNodePatch(NodeSnapshot negativeNode, Diff diff, boolean reservePreviousRef) {
        if (diff instanceof ElementDiff) {
            return applyElementPatch((ElementSnapshot) negativeNode, (ElementDiff) diff, reservePreviousRef);
        } else if (diff instanceof TextNodeDiff) {
            return applyTextNodePatch(((TextNodeSnapshot) negativeNode), (TextNodeDiff) diff, reservePreviousRef);
        } else {
            throw new RuntimeException();
        }
    }

    /**
     * Apply patch to element, return new patched element.
     *
     * @param negativeElement
     * @param diff
     * @param reservePreviousRef
     * @return A new patched element object, with previous node reference refer
     * to the <code>negativeElement</code>.
     */
    ElementSnapshot applyElementPatch(ElementSnapshot negativeElement, ElementDiff diff, boolean reservePreviousRef) {
        if (diff == null || (reservePreviousRef && negativeElement == null)) {
            throw new IllegalArgumentException();
        }

        String tagName = diff.getTagName();

        Map<String, String> attrs = new LinkedHashMap<>();
        if (negativeElement != null) {
            for (AttributeSnapshot attr : negativeElement.getAttributes()) {
                attrs.put(attr.getName(), attr.getValue());
            }
        }

        for (Map.Entry<String, String> negAttrEntry : diff.getNegativeAttributes().entrySet()) {
            if (!attrs.containsKey(negAttrEntry.getKey())) {
                throw new IllegalStateException();
            }
            String removed = attrs.remove(negAttrEntry.getKey());
            // check removed.equals(negAttrEntry.getValue()); ?
        }

        for (Map.Entry<String, String> posAttr : diff.getPositiveAttributes().entrySet()) {
            if (attrs.containsKey(posAttr.getKey())) {
                throw new IllegalStateException();
            }
            attrs.put(posAttr.getKey(), posAttr.getValue());
        }

        List<AttributeSnapshot> attrList = new ArrayList<>(attrs.size());
        attrs.forEach((name, value) -> attrList.add(new AttributeSnapshotImpl(name, value, null)));

        List<NodeSnapshot> childList = negativeElement == null ?
                Collections.emptyList() : new ArrayList<>(negativeElement.getChildren());

        ElementSnapshot previousNode = reservePreviousRef ?
                negativeElement.getPreviousSnapshot() : negativeElement;

        return new ElementSnapshotImpl(tagName, attrList, childList, previousNode);
    }

    /**
     * Apply patch to text node, return new patched text node.
     *
     * @param negativeText
     * @param diff
     * @param reservePreviousRef
     * @return A new patched text node object, with previous node reference
     * refer to the <code>negativeText</code>.
     */
    TextNodeSnapshot applyTextNodePatch(TextNodeSnapshot negativeText, TextNodeDiff diff, boolean reservePreviousRef) {
        if (diff == null || (reservePreviousRef && negativeText == null)) {
            throw new IllegalArgumentException();
        }

        LineArrayWrapper nSeq = new LineArrayWrapper(negativeText == null ?
                null : negativeText.getData());
        StringBuilder sb = new StringBuilder();
        int ni = 0, pi = 0;

        // here assume LineDiff objects is well ordered, as produced by Myers difference algorithm.
        for (LineDiff lineDiff : diff.getLines()) {
            int op = lineDiff.getOp();
            if (op < 0) {
                int nPos = -op - 1;
                for (; ni < nPos; ni++, pi++) {
                    LineArrayWrapper.LineWrapper ln = nSeq.get(ni);
                    sb.append(ln.getOriginText(), ln.getStart(), ln.getEnd());
                }
                ni++;

            } else {
                int pPos = op - 1;
                for (; pi < pPos; ni++, pi++) {
                    LineArrayWrapper.LineWrapper ln = nSeq.get(ni);
                    sb.append(ln.getOriginText(), ln.getStart(), ln.getEnd());
                }
                sb.append(lineDiff.getContent());
                pi++;
            }
        }
        for (; ni < nSeq.size(); ni++) {
            LineArrayWrapper.LineWrapper ln = nSeq.get(ni);
            sb.append(ln.getOriginText(), ln.getStart(), ln.getEnd());
        }

        String data = sb.toString();
        if (data.isEmpty() && negativeText != null && negativeText.getData() == null) {
            data = null; // TODO treat null as empty string?
        }

        NodeSnapshot previous = reservePreviousRef ?
                negativeText.getPreviousSnapshot() : negativeText;

        return new TextNodeSnapshotImpl(data, negativeText);
    }

    private NodeSnapshot getNodeByLocation(NodeSnapshot root, NodeLocation location) {
        return getNodeByLocation(root, NodeLocation.root(), location);
    }

    private NodeSnapshot getNodeByLocation(NodeSnapshot node, NodeLocation nodeLocation, NodeLocation targetLocation) {
        if (nodeLocation.equals(targetLocation)) {
            return node;
        }

        if (!nodeLocation.isAncestorOf(targetLocation)) {
            throw new IllegalArgumentException();
        }

        for (int i = nodeLocation.getDepth();
             i < targetLocation.getDepth() && node != null;
             i++) {
            int index = targetLocation.getIndexOfLevel(i);
            List<NodeSnapshot> children = ((ElementSnapshot) node).getChildren();
            if (index >= children.size()) {
                return null;
            }
            node = children.get(index);
        }

        return node;
    }

    abstract class TreePatchStack extends ShiftAndReduceStack<NodeLocation> {
        @Override
        protected boolean tryReduce(NodeLocation inputLocation) {
            if (isEmpty()) {
                return false;
            }

            if (inputLocation != null) {
                if (inputLocation.isSibling(peek()) ||
                        inputLocation.isDescendantOf(peek())) {
                    return false;
                }
            }

            NodeLocation location = pop();
            TreeSet<Integer> collection = new TreeSet<>();
            collection.add(location.leafIndex());
            while (!isEmpty() && location.isSibling(peek())) {
                collection.add(pop().leafIndex());
            }

            NodeLocation parentLocation = location.getParent();
            if (parentLocation == null) {
                if (inputLocation != null) {
                    throw new RuntimeException("Unexpected state encountered, this is probably a bug.");
                }
                doReduce(null, collection);
                return false;
            }

            doReduce(parentLocation, collection);

            return true;
        }

        protected abstract void doReduce(NodeLocation parentLocation, NavigableSet<Integer> collection);
    }

    class TreeDeleteStack extends TreePatchStack {
        private ElementSnapshot negativeTree;
        private NavigableSet<NodeLocation> deletions;
        private Map<NodeLocation, NodeSnapshot> dirtyNodes;

        /**
         * Construct a stack for deleting tree nodes.
         *
         * @param negativeTree original tree.
         * @param deletions    sorted deletion list.
         * @param dirtyNodes   used to hold dirty nodes after deleting.
         *                     a dirty node is always a new object with
         *                     previous node reference refer to the origin
         *                     node in the <code>negativeTree</code>
         */
        TreeDeleteStack(ElementSnapshot negativeTree,
                        NavigableSet<NodeLocation> deletions,
                        Map<NodeLocation, NodeSnapshot> dirtyNodes) {
            this.negativeTree = negativeTree;
            this.deletions = deletions;
            this.dirtyNodes = dirtyNodes;
        }

        @Override
        protected void doReduce(NodeLocation parentLocation, NavigableSet<Integer> deletedIndices) {
            if (parentLocation == null) {
                if (deletedIndices.size() != 1) {
                    throw new IllegalStateException();
                }
                if (dirtyNodes.isEmpty()) {
                    dirtyNodes.put(NodeLocation.root(), negativeTree);
                }
                return;
            }

            ElementSnapshot element = (ElementSnapshot) getNodeByLocation(negativeTree, parentLocation);
            List<NodeSnapshot> priorChildren = element.getChildren();
            List<NodeSnapshot> nextChildren = new ArrayList<>(priorChildren.size() - deletedIndices.size());
            for (int i = 0; i < priorChildren.size(); i++) {
                NodeLocation childLocation = parentLocation.append(i);
                if (deletions.contains(childLocation)) {
                    continue;
                }
                NodeSnapshot child = dirtyNodes.get(childLocation);
                if (child == null) {
                    child = priorChildren.get(i);
                }
                nextChildren.add(child);
            }

            ElementSnapshotImpl dirtyElement = new ElementSnapshotImpl(
                    element.getTagName(),
                    element.getAttributes(),
                    nextChildren,
                    element);
            dirtyNodes.put(parentLocation, dirtyElement);

            if (isEmpty() || !parentLocation.equals(peek())) {
                feed(parentLocation);
            }
        }
    }

    class TreeInsertStack extends TreePatchStack {
        private ElementSnapshot negativeTree;
        private NavigableMap<NodeLocation, Diff> insertions;
        private NavigableMap<NodeLocation, NodeSnapshot> positiveTempNodes;

        /**
         * Construct a stack for tree nodes insertion.
         *
         * @param negativeTree      the origin tree before any update.
         * @param positiveTempNodes temp nodes indexed by positive locations.
         * @param insertions        sorted insertion map of location-diff pairs.
         */
        public TreeInsertStack(ElementSnapshot negativeTree,
                               NavigableMap<NodeLocation, NodeSnapshot> positiveTempNodes,
                               NavigableMap<NodeLocation, Diff> insertions) {
            this.negativeTree = negativeTree;
            this.positiveTempNodes = positiveTempNodes;
            this.insertions = insertions;
        }

        // TODO review for previous ref
        @Override
        protected void doReduce(NodeLocation parentLocation, NavigableSet<Integer> insertIndices) {
            if (parentLocation == null) {
                return; // last reduce
            }

            boolean reservePreviousRef = false;
            ElementSnapshot parentElem = (ElementSnapshot) positiveTempNodes.get(parentLocation);
            if (parentElem != null) {
                reservePreviousRef = true;
            } else {
                Map.Entry<NodeLocation, NodeSnapshot> ancestor = positiveTempNodes.floorEntry(parentLocation);
                if (ancestor != null) {
                    parentElem = (ElementSnapshot) getNodeByLocation(ancestor.getValue(),
                            ancestor.getKey(), parentLocation);

                } else {
                    parentElem = (ElementSnapshot) getNodeByLocation(negativeTree, parentLocation);
                    if (parentElem != null) {
                        reservePreviousRef = true;
                    }
                }
            }

            if (parentElem == null) {
                throw new IllegalStateException();
            }

            List<NodeSnapshot> priorChildren = parentElem.getChildren();
            List<NodeSnapshot> nextChildren = new ArrayList<>(priorChildren.size() + insertIndices.size());

            int endIndex = priorChildren.size() + insertIndices.size();
            for (int negativeIndex = 0, positiveIndex = 0;
                 positiveIndex < endIndex;
                 positiveIndex++) {

                NodeLocation childLocation = parentLocation.append(positiveIndex);
                NodeSnapshot child = positiveTempNodes.get(childLocation);
                if (child == null) {
                    child = priorChildren.get(negativeIndex++);
                }

                if (child == null) {
                    throw new IllegalStateException();
                }

                nextChildren.add(child);
            }

            parentElem = new ElementSnapshotImpl(parentElem.getTagName(),
                    parentElem.getAttributes(),
                    nextChildren,
                    parentElem.getPreviousSnapshot()); // FIXME previous node is not correct

            positiveTempNodes.put(parentLocation, parentElem);

            if (isEmpty() || !parentLocation.equals(peek())) {
                feed(parentLocation);
            }
        }
    }

}