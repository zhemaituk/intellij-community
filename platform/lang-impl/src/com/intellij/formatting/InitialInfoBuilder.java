/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.formatting;

import com.intellij.formatting.engine.ExpandableIndent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UnfairTextRange;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.ReadOnlyBlockInformationProvider;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.LinkedMultiMap;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.Stack;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allows to build {@link AbstractBlockWrapper formatting block wrappers} for the target {@link Block formatting blocks}.
 * The main idea of block wrapping is to associate information about {@link WhiteSpace white space before block} with the block itself.
 */
public class InitialInfoBuilder {

  private final Map<AbstractBlockWrapper, Block> myResult = new THashMap<>();

  private final FormattingDocumentModel               myModel;
  private final FormatTextRanges                      myAffectedRanges;
  private final int                                   myPositionOfInterest;
  @NotNull
  private final FormattingProgressCallback            myProgressCallback;
  private final FormatterTagHandler                   myFormatterTagHandler;

  private final CommonCodeStyleSettings.IndentOptions myOptions;

  private final Stack<FormatterBuilderState> myStates = new Stack<>();
  
  private WhiteSpace                       myCurrentWhiteSpace;
  private CompositeBlockWrapper            myRootBlockWrapper;
  private LeafBlockWrapper                 myPreviousBlock;
  private LeafBlockWrapper                 myFirstTokenBlock;
  private LeafBlockWrapper                 myLastTokenBlock;
  private SpacingImpl                      myCurrentSpaceProperty;
  private ReadOnlyBlockInformationProvider myReadOnlyBlockInformationProvider;
  private boolean                          myInsideFormatRestrictingTag;

  private static final boolean INLINE_TABS_ENABLED = "true".equalsIgnoreCase(System.getProperty("inline.tabs.enabled"));

  private final List<TextRange> myExtendedAffectedRanges;
  private Set<Alignment> myAlignmentsInsideRangeToModify = ContainerUtil.newHashSet();
  private boolean myCollectAlignmentsInsideFormattingRange = false;

  private static final RangesAssert myRangesAssert = new RangesAssert();

  private MultiMap<ExpandableIndent, AbstractBlockWrapper> myBlocksToForceChildrenIndent = new LinkedMultiMap<>();
  private MultiMap<Alignment, Block> myBlocksToAlign = new MultiMap<>();

  private InitialInfoBuilder(final Block rootBlock,
                             final FormattingDocumentModel model,
                             @Nullable final FormatTextRanges affectedRanges,
                             @NotNull CodeStyleSettings settings,
                             final CommonCodeStyleSettings.IndentOptions options,
                             final int positionOfInterest,
                             @NotNull FormattingProgressCallback progressCallback)
  {
    myModel = model;
    myAffectedRanges = affectedRanges;
    myExtendedAffectedRanges = getExtendedAffectedRanges(affectedRanges);
    myProgressCallback = progressCallback;
    myCurrentWhiteSpace = new WhiteSpace(getStartOffset(rootBlock), true);
    myOptions = options;
    myPositionOfInterest = positionOfInterest;
    myInsideFormatRestrictingTag = false;
    myFormatterTagHandler = new FormatterTagHandler(settings);
  }

  protected static InitialInfoBuilder prepareToBuildBlocksSequentially(
    Block root, 
    FormattingDocumentModel model, 
    FormatProcessor.FormatOptions formatOptions, 
    CodeStyleSettings settings, 
    CommonCodeStyleSettings.IndentOptions options, 
    @NotNull FormattingProgressCallback progressCallback) 
  {
    InitialInfoBuilder builder = new InitialInfoBuilder(root, model, formatOptions.myAffectedRanges, settings, options, formatOptions.myInterestingOffset, progressCallback);
    builder.setCollectAlignmentsInsideFormattingRange(formatOptions.myReformatContext);
    builder.buildFrom(root, 0, null, null, null, true);
    return builder;
  }

  private int getStartOffset(@NotNull Block rootBlock) {
    int minOffset = rootBlock.getTextRange().getStartOffset();
    if (myAffectedRanges != null) {
      for (FormatTextRanges.FormatTextRange range : myAffectedRanges.getRanges()) {
        if (range.getStartOffset() < minOffset) minOffset = range.getStartOffset();
      }
    }
    return minOffset;
  }
  
  public FormattingDocumentModel getFormattingDocumentModel() {
    return myModel;
  }

  public int getEndOffset() {
    int maxDocOffset = myModel.getTextLength();
    int maxOffset = myRootBlockWrapper != null ? myRootBlockWrapper.getEndOffset() : 0;
    if (myAffectedRanges != null) {
      for (FormatTextRanges.FormatTextRange range : myAffectedRanges.getRanges()) {
        if (range.getTextRange().getEndOffset() > maxOffset) maxOffset = range.getTextRange().getEndOffset();
      }
    }
    return   maxOffset < maxDocOffset ? maxOffset : maxDocOffset;
  }

  public boolean iteration() {
    if (myStates.isEmpty()) {
      return true;
    }

    FormatterBuilderState state = myStates.peek();
    doIteration(state);
    return myStates.isEmpty();
  }
  
  private AbstractBlockWrapper buildFrom(final Block rootBlock,
                                         final int index,
                                         @Nullable final CompositeBlockWrapper parent,
                                         @Nullable WrapImpl currentWrapParent,
                                         @Nullable final Block parentBlock,
                                         boolean rootBlockIsRightBlock)
  {
    final WrapImpl wrap = (WrapImpl)rootBlock.getWrap();
    if (wrap != null) {
      wrap.registerParent(currentWrapParent);
      currentWrapParent = wrap;
    }
    
    TextRange textRange = rootBlock.getTextRange();
    final int blockStartOffset = textRange.getStartOffset();

    if (parent != null) {
      checkRanges(parent, textRange);
    }

    myCurrentWhiteSpace.append(blockStartOffset, myModel, myOptions);

    if (myCollectAlignmentsInsideFormattingRange && rootBlock.getAlignment() != null
        && isAffectedByFormatting(rootBlock) && !myInsideFormatRestrictingTag)
    {
      myAlignmentsInsideRangeToModify.add(rootBlock.getAlignment());
    }

    if (rootBlock.getAlignment() != null) {
      myBlocksToAlign.putValue(rootBlock.getAlignment(), rootBlock);
    }

    ReadOnlyBlockInformationProvider previousProvider = myReadOnlyBlockInformationProvider;
    try {
      if (rootBlock instanceof ReadOnlyBlockInformationProvider) {
        myReadOnlyBlockInformationProvider = (ReadOnlyBlockInformationProvider)rootBlock;
      }
      
      if (isInsideFormattingRanges(rootBlock, rootBlockIsRightBlock)
          || myCollectAlignmentsInsideFormattingRange && isInsideExtendedAffectedRange(rootBlock))
      {
        final List<Block> subBlocks = rootBlock.getSubBlocks();
        if (subBlocks.isEmpty() || myReadOnlyBlockInformationProvider != null && myReadOnlyBlockInformationProvider.isReadOnly(rootBlock)) {
          final AbstractBlockWrapper wrapper = processSimpleBlock(rootBlock, parent, false, index, parentBlock);
          if (!subBlocks.isEmpty()) {
            wrapper.setIndent((IndentImpl)subBlocks.get(0).getIndent());
          }
          return wrapper;
        }
        return buildCompositeBlock(rootBlock, parent, index, currentWrapParent, rootBlockIsRightBlock);
      }
      else {
        //block building is skipped
        return processSimpleBlock(rootBlock, parent, true, index, parentBlock);
      }
    }
    finally {
      myReadOnlyBlockInformationProvider = previousProvider;
    }
  }

  private void checkRanges(@NotNull CompositeBlockWrapper parent, TextRange textRange) {
    if (textRange.getStartOffset() < parent.getStartOffset()) {
      myRangesAssert.assertInvalidRanges(
        textRange.getStartOffset(),
        parent.getStartOffset(),
        myModel,
        "child block start is less than parent block start"
      );
    }

    if (textRange.getEndOffset() > parent.getEndOffset()) {
      myRangesAssert.assertInvalidRanges(
        textRange.getEndOffset(),
        parent.getEndOffset(),
        myModel,
        "child block end is after parent block end"
      );
    }
  }

  private boolean isInsideExtendedAffectedRange(Block rootBlock) {
    if (myExtendedAffectedRanges == null) return false;

    TextRange blockRange = rootBlock.getTextRange();
    for (TextRange affectedRange : myExtendedAffectedRanges) {
      if (affectedRange.intersects(blockRange)) return true;
    }

    return false;
  }

  @Nullable
  private static List<TextRange> getExtendedAffectedRanges(FormatTextRanges formatTextRanges) {
    if (formatTextRanges == null) return null;

    List<FormatTextRanges.FormatTextRange> ranges = formatTextRanges.getRanges();
    List<TextRange> extended = ContainerUtil.newArrayList();

    final int extendOffset = 500;
    for (FormatTextRanges.FormatTextRange textRange : ranges) {
      TextRange range = textRange.getTextRange();
      extended.add(new UnfairTextRange(range.getStartOffset() - extendOffset, range.getEndOffset() + extendOffset));
    }

    return extended;
  }

  private CompositeBlockWrapper buildCompositeBlock(final Block rootBlock,
                                   @Nullable final CompositeBlockWrapper parent,
                                   final int index,
                                   @Nullable final WrapImpl currentWrapParent,
                                   boolean rootBlockIsRightBlock)
  {
    final CompositeBlockWrapper wrappedRootBlock = new CompositeBlockWrapper(rootBlock, myCurrentWhiteSpace, parent);
    if (index == 0) {
      wrappedRootBlock.arrangeParentTextRange();
    }

    if (myRootBlockWrapper == null) {
      myRootBlockWrapper = wrappedRootBlock;
      myRootBlockWrapper.setIndent((IndentImpl)Indent.getNoneIndent());
    }
    boolean blocksMayBeOfInterest = false;

    if (myPositionOfInterest != -1) {
      myResult.put(wrappedRootBlock, rootBlock);
      blocksMayBeOfInterest = true;
    }
    
    final boolean blocksAreReadOnly = rootBlock instanceof ReadOnlyBlockContainer || blocksMayBeOfInterest;
    
    FormatterBuilderState
      state = new FormatterBuilderState(rootBlock, wrappedRootBlock, currentWrapParent, blocksAreReadOnly, rootBlockIsRightBlock);
    myStates.push(state);
    return wrappedRootBlock;
  }

  public MultiMap<ExpandableIndent, AbstractBlockWrapper> getExpandableIndentsBlocks() {
    return myBlocksToForceChildrenIndent;
  }
  
  public MultiMap<Alignment, Block> getBlocksToAlign() {
    return myBlocksToAlign;
  }
  
  private void doIteration(@NotNull FormatterBuilderState state) {
    Block currentRoot = state.parentBlock;
    
    List<Block> subBlocks = currentRoot.getSubBlocks();
    int currentBlockIndex = state.getIndexOfChildBlockToProcess();
    final Block currentBlock = subBlocks.get(currentBlockIndex);

    initCurrentWhiteSpace(currentRoot, state.previousBlock, currentBlock);

    boolean childBlockIsRightBlock = state.parentBlockIsRightBlock && currentBlockIndex == subBlocks.size() - 1;
    
    final AbstractBlockWrapper wrapper = buildFrom(
      currentBlock, currentBlockIndex, state.wrappedBlock, state.parentBlockWrap, currentRoot, childBlockIsRightBlock
    );
    
    registerExpandableIndents(currentBlock, wrapper);

    if (wrapper.getIndent() == null) {
      wrapper.setIndent((IndentImpl)currentBlock.getIndent());
    }
    if (!state.readOnly) {
      try {
        subBlocks.set(currentBlockIndex, null); // to prevent extra strong refs during model building
      } catch (Throwable ex) {
        // read-only blocks
      }
    }
    
    if (state.childBlockProcessed(currentBlock, wrapper, myOptions)) {
      while (!myStates.isEmpty() && myStates.peek().isProcessed()) {
        myStates.pop();
      }
    }
  }
  
  private void initCurrentWhiteSpace(@NotNull Block currentRoot, @Nullable Block previousBlock, @NotNull Block currentBlock) {
    if (previousBlock != null || (myCurrentWhiteSpace != null && myCurrentWhiteSpace.isIsFirstWhiteSpace())) {
      myCurrentSpaceProperty = (SpacingImpl)currentRoot.getSpacing(previousBlock, currentBlock);
    }
  }

  private void registerExpandableIndents(@NotNull Block block, @NotNull AbstractBlockWrapper wrapper) {
    if (block.getIndent() instanceof ExpandableIndent) {
      ExpandableIndent indent = (ExpandableIndent)block.getIndent();
      myBlocksToForceChildrenIndent.putValue(indent, wrapper);
    }
  }

  public static void setDefaultIndents(final List<AbstractBlockWrapper> list, boolean useRelativeIndents) {
    for (AbstractBlockWrapper wrapper : list) {
      if (wrapper.getIndent() == null) {
        wrapper.setIndent((IndentImpl)Indent.getContinuationWithoutFirstIndent(useRelativeIndents));
      }
    }
  }

  private AbstractBlockWrapper processSimpleBlock(final Block rootBlock,
                                                  @Nullable final CompositeBlockWrapper parent,
                                                  final boolean readOnly,
                                                  final int index,
                                                  @Nullable Block parentBlock) 
  {
    LeafBlockWrapper result = doProcessSimpleBlock(rootBlock, parent, readOnly, index, parentBlock);
    myProgressCallback.afterWrappingBlock(result);
    return result;
  }

  private LeafBlockWrapper doProcessSimpleBlock(final Block rootBlock,
                                                @Nullable final CompositeBlockWrapper parent,
                                                final boolean readOnly,
                                                final int index,
                                                @Nullable Block parentBlock)
  {
    if (!INLINE_TABS_ENABLED && !myCurrentWhiteSpace.containsLineFeeds()) {
      myCurrentWhiteSpace.setForceSkipTabulationsUsage(true);
    }
    final LeafBlockWrapper info =
      new LeafBlockWrapper(rootBlock, parent, myCurrentWhiteSpace, myModel, myOptions, myPreviousBlock, readOnly);
    if (index == 0) {
      info.arrangeParentTextRange();
    }

    switch (myFormatterTagHandler.getFormatterTag(rootBlock)) {
      case ON:
        myInsideFormatRestrictingTag = false;
        break;
      case OFF:
        myInsideFormatRestrictingTag = true;
        break;
      case NONE:
        break;
    }

    TextRange textRange = rootBlock.getTextRange();
    if (textRange.getLength() == 0) {
      myRangesAssert.assertInvalidRanges(
        textRange.getStartOffset(),
        textRange.getEndOffset(),
        myModel,
        "empty block"
      );
    }
    if (myPreviousBlock != null) {
      myPreviousBlock.setNextBlock(info);
    }
    if (myFirstTokenBlock == null) {
      myFirstTokenBlock = info;
    }
    myLastTokenBlock = info;
    if (currentWhiteSpaceIsReadOnly()) {
      myCurrentWhiteSpace.setReadOnly(true);
    }
    if (myCurrentSpaceProperty != null) {
      myCurrentWhiteSpace.setIsSafe(myCurrentSpaceProperty.isSafe());
      myCurrentWhiteSpace.setKeepFirstColumn(myCurrentSpaceProperty.shouldKeepFirstColumn());
    }

    if (info.isEndOfCodeBlock()) {
      myCurrentWhiteSpace.setBeforeCodeBlockEnd(true);
    }

    info.setSpaceProperty(myCurrentSpaceProperty);
    myCurrentWhiteSpace = new WhiteSpace(textRange.getEndOffset(), false);
    if (myInsideFormatRestrictingTag) myCurrentWhiteSpace.setReadOnly(true);
    myPreviousBlock = info;

    if (myPositionOfInterest != -1 && (textRange.contains(myPositionOfInterest) || textRange.getEndOffset() == myPositionOfInterest)) {
      myResult.put(info, rootBlock);
      if (parent != null) myResult.put(parent, parentBlock);
    }
    return info;
  }

  private boolean currentWhiteSpaceIsReadOnly() {
    if (myCurrentSpaceProperty != null && myCurrentSpaceProperty.isReadOnly()) {
      return true;
    }
    else {
      if (myAffectedRanges == null) return false;
      return myAffectedRanges.isWhitespaceReadOnly(myCurrentWhiteSpace.getTextRange());
    }
  }

  private boolean isAffectedByFormatting(final Block block) {
    if (myAffectedRanges == null) return true;

    List<FormatTextRanges.FormatTextRange> allRanges = myAffectedRanges.getRanges();
    Document document = myModel.getDocument();
    int docLength = document.getTextLength();
    
    for (FormatTextRanges.FormatTextRange range : allRanges) {
      int startOffset = range.getStartOffset();
      if (startOffset >= docLength) continue;
      
      int lineNumber = document.getLineNumber(startOffset);
      int lineEndOffset = document.getLineEndOffset(lineNumber);

      int blockStartOffset = block.getTextRange().getStartOffset();
      if (blockStartOffset >= startOffset && blockStartOffset < lineEndOffset) {
        return true;
      }
    }
    
    return false;
  }

  private boolean isInsideFormattingRanges(final Block block, boolean rootIsRightBlock) {
    if (myAffectedRanges == null) return true;
    return !myAffectedRanges.isReadOnly(block.getTextRange(), rootIsRightBlock);
  }

  public Map<AbstractBlockWrapper, Block> getBlockToInfoMap() {
    return myResult;
  }

  public LeafBlockWrapper getFirstTokenBlock() {
    return myFirstTokenBlock;
  }

  public LeafBlockWrapper getLastTokenBlock() {
    return myLastTokenBlock;
  }
  
  public Set<Alignment> getAlignmentsInsideRangeToModify() {
    return myAlignmentsInsideRangeToModify;
  }

  public void setCollectAlignmentsInsideFormattingRange(boolean value) {
    myCollectAlignmentsInsideFormattingRange = value;
  }
  
}


