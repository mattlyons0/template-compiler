/**
 * Copyright (c) 2014 SQUARESPACE, Inc.
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

package com.squarespace.template;

import static com.squarespace.template.ExecuteErrorType.APPLY_PARTIAL_RECURSION_DEPTH;
import static com.squarespace.template.ExecuteErrorType.UNEXPECTED_ERROR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.squarespace.cldrengine.CLDR;
import com.squarespace.cldrengine.api.CLocale;


/**
 * Tracks all of the state needed for executing a template against a given JSON tree.
 *
 * Compilation converts the raw text into an instruction tree. The instruction tree
 * is stateless and can be reused across multiple executions.
 *
 * The Context is used to carry out a single execution of the template instruction tree.
 * Each execution of a template requires a fresh context object.
 */
public class Context {

  private static final JsonNode DEFAULT_UNDEFINED = MissingNode.getInstance();

  private static final String META_LEFT = "{";

  private static final String META_RIGHT = "}";

  private Locale javaLocale;

  private CLDR cldrengine;

  private MessageFormats messageformats;

  private Compiler compiler;

  private Frame currentFrame;

  private JsonNode undefined = DEFAULT_UNDEFINED;

  private boolean safeExecution = false;

  private boolean preprocess = false;

  private int maxPartialDepth = Constants.DEFAULT_MAX_PARTIAL_DEPTH;

  private List<ErrorInfo> errors;

  /**
   * Reference to the currently-executing instruction. All instruction execution
   * must pass control via the Context, for proper error handling.
   */
  private Instruction currentInstruction;

  private JsonNode rawPartials;

  private Map<String, Instruction> compiledPartials;

  private JsonNode rawInjectables;

  private Map<String, JsonNode> parsedInjectables;

  private int partialDepth;

  private Long now = null;

  private LoggingHook loggingHook;

  private CodeLimiter codeLimiter = new NoopCodeLimiter();

  /* Holds the final output of the template execution */
  private StringBuilder buf;

  public Context(JsonNode node) {
    this(node, new StringBuilder(), null);
  }

  public Context(JsonNode node, Locale locale) {
    this(node, new StringBuilder(), locale);
  }

  public Context(JsonNode node, StringBuilder buf, Locale locale) {
    this.currentFrame = new Frame(null, node == null ? MissingNode.getInstance() : node);
    this.buf = buf == null ? new StringBuilder() : buf;
    this.javaLocale = locale == null ? Locale.US : locale;
  }

  public boolean safeExecutionEnabled() {
    return safeExecution;
  }

  public List<ErrorInfo> getErrors() {
    return (errors == null) ? Collections.<ErrorInfo>emptyList() : errors;
  }

  public Locale javaLocale() {
    return javaLocale;
  }

  public void javaLocale(Locale locale) {
    this.javaLocale = locale;
  }

  public void now(Long value) {
    this.now = value;
  }

  public Long now() {
    return now;
  }

//  public void cldrLocale(CLDR.Locale locale) {
//    this.cldrLocale = locale;
//  }
//
//  public CLDR.Locale cldrLocale() {
//    return cldrLocale;
//  }

  public MessageFormats messageFormatter() {
    if (this.messageformats == null) {
      this.messageformats = new MessageFormats(this.cldr());
    }
    return this.messageformats;
  }

  public CLDR cldr() {
    if (cldrengine == null) {
      String tag = javaLocale != null ? this.javaLocale.toLanguageTag() : "en-US";
      this.cldrengine = com.squarespace.cldrengine.CLDR.get(tag);
    }
    return cldrengine;
  }

  public void cldrengine(CLDR cldr) {
    this.cldrengine = cldr;
  }

  public void cldrengine(String id) {
    this.cldrengine = CLDR.get(id);
  }

  public void cldrengine(CLocale locale) {
    this.cldrengine = CLDR.get(locale);
  }

  /**
   * Set mode where no exceptions will be thrown; instead
   */
  public void setSafeExecution() {
    this.safeExecution = true;
  }

  /**
   * Enable pre-processor mode.
   */
  public void setPreprocess(boolean preprocess) {
    this.preprocess = preprocess;
  }

  public void setMaxPartialDepth(int depth) {
    this.maxPartialDepth = Math.max(0, depth);
  }

  public CharSequence getMetaLeft() {
    return META_LEFT;
  }

  public CharSequence getMetaRight() {
    return META_RIGHT;
  }

  /**
   * Swap the buffer for the current formatter.
   */
  public StringBuilder swapBuffer(StringBuilder newBuffer) {
    StringBuilder tmp = buf;
    buf = newBuffer;
    return tmp;
  }

  /**
   * Sets a compiler to be used for compiling partials. If no compiler is set,
   * partials cannot be compiled and will raise errors.
   */
  public void setCompiler(Compiler compiler) {
    this.compiler = compiler;
  }

  public Compiler getCompiler() {
    return compiler;
  }

  public void setLoggingHook(LoggingHook hook) {
    this.loggingHook = hook;
  }

  public CodeLimiter getCodeLimiter() {
    return codeLimiter;
  }

  public void setCodeLimiter(CodeLimiter limiter) {
    this.codeLimiter = limiter;
  }

  /**
   * Execute a single instruction.
   */
  public void execute(Instruction instruction) throws CodeExecuteException {
    if (instruction == null) {
      return;
    }
    currentInstruction = instruction;
    try {
      codeLimiter.check();
      instruction.invoke(this);

    } catch (CodeExecuteException e) {
      // This is thrown explicitly when an instruction / plugin needs to
      // abort execution. Instructions and plugins must first check if
      // safe execution mode is enabled before throwing. This gives us
      // the flexibility to abort execution even when safe mode is enabled
      // for severe errors, or when a hard resource limit is reached.
      throw e;

    } catch (Exception e) {
      String repr = ReprEmitter.get(instruction, false);
      ErrorInfo error = error(UNEXPECTED_ERROR)
          .name(e.getClass().getSimpleName())
          .data(e.getMessage())
          .repr(repr);

      // In safe mode we don't raise exceptions; just append the error.
      if (safeExecution) {
        addError(error);
      } else {
        throw new CodeExecuteException(error, e);
      }

      // If a logging hook exists, always log the unexpected exception.
      log(e);
    }
  }

  /**
   * Execute a list of instructions.
   */
  public void execute(List<Instruction> instructions) throws CodeExecuteException {
    if (instructions != null) {
      int size = instructions.size();
      for (int i = 0; i < size; i++) {
        execute(instructions.get(i));
      }
    }
  }

  public ErrorInfo error(ExecuteErrorType code) {
    ErrorInfo info = new ErrorInfo(code);
    info.code(code);
    info.line(currentInstruction.getLineNumber());
    info.offset(currentInstruction.getCharOffset());
    return info;
  }

  /**
   * Lazily allocate the compiled partials cache.
   */
  public void setPartials(JsonNode node) {
    this.rawPartials = node;
    this.compiledPartials = new HashMap<>();
  }

  /**
   * Returns the root instruction for a compiled partial, assuming the partial exists
   * in the partials map. Compiled partials are cached for reuse within the same
   * context, since a partial may be applied multiple times within a template, or
   * inside a loop.
   */
  public Instruction getPartial(String name) throws CodeSyntaxException {
    // Macros override partials, so if one is defined in the current scope, return it.
    Instruction inst = resolveMacro(name);
    if (inst != null) {
      return inst;
    }

    if (rawPartials == null) {
      // Template wants to use a partial but none are defined.
      return null;
    }

    // See if we've previously compiled this exact partial.
    inst = compiledPartials.get(name);
    if (inst == null) {
      JsonNode partialNode = rawPartials.get(name);
      if (partialNode == null) {
        // Indicate partial is missing.
        return null;
      }
      if (!partialNode.isTextual()) {
        // Should we bother worrying about this, or just cast the node to text?
        return null;
      }

      // Compile the partial.  This can throw a syntax exception, which the formatter
      // will catch and nest inside a runtime exception.
      String source = partialNode.asText();
      CompiledTemplate template = compiler.compile(source, safeExecution, preprocess);
      if (safeExecution) {
        List<ErrorInfo> errors = template.errors();
        if (!errors.isEmpty()) {
          ErrorInfo parent = error(ExecuteErrorType.COMPILE_PARTIAL_SYNTAX).name(name);
          parent.child(errors);
          addError(parent);
        }
      }

      // Cache the compiled template in case it is used more than once.
      inst = template.code();
      compiledPartials.put(name, inst);
    }
    return inst;
  }

  /**
   * Check if we're about to recurse through a partial we're already evaluating.
   * This code currently prevents all reentrant evaluation of partials.
   *
   * NOTE: The template team will need to weigh in on whether we currently have
   * partials which recurse but properly terminate recursion. For now this code treats
   * all recursion as an error.
   */
  public boolean enterPartial(String name) throws CodeExecuteException {
    // Limit maximum partial recursion depth
    partialDepth++;
    if (partialDepth > maxPartialDepth) {
      ErrorInfo error = error(APPLY_PARTIAL_RECURSION_DEPTH)
          .name(name)
          .data(maxPartialDepth);
      if (safeExecution) {
        addError(error);
        return false;
      } else {
        throw new CodeExecuteException(error);
      }
    }
    return true;
  }

  /**
   * Clears flag indicating we're executing inside a partial template.
   */
  public void exitPartial(String name) {
    partialDepth--;
  }

  /**
   * Lazily allocate the injectable JSON cache.
   */
  public void setInjectables(JsonNode node) {
    this.rawInjectables = node;
    this.parsedInjectables = new HashMap<>();
  }

  /**
   * Retrieve an injectable JSON object by name.
   */
  public JsonNode getInjectable(String name) {
    if (rawInjectables == null) {
      return Constants.MISSING_NODE;
    }

    // Check if we've already parsed and cached this injectable
    JsonNode result = parsedInjectables.get(name);
    if (result == null) {

      // Not cached, so parse the injectable if it exists.
      JsonNode rawNode = rawInjectables.get(name);
      if (rawNode != null) {
        String raw = rawNode.asText();
        result = JsonUtils.decode(raw, true);
      } else {
        result = Constants.MISSING_NODE;
      }

      // Update the mapping even if it is MISSING to save a lookup.
      parsedInjectables.put(name, result);
    }

    return result;
  }


  public StringBuilder buffer() {
    return buf;
  }

  public JsonNode node() {
    return currentFrame.node();
  }

  public boolean initIteration() {
    JsonNode node = node();
    if (!node.isArray() || node.size() == 0) {
      return false;
    }
    currentFrame.currentIndex = 0;
    return true;
  }

  /**
   * Use this to find the index position in the current frame.
   */
  public int currentIndex() {
    return currentFrame.currentIndex;
  }

  public boolean hasNext() {
    return currentFrame.currentIndex < currentFrame.node().size();
  }

  /**
   * Return the current frame's array size.
   */
  public int arraySize() {
    return currentFrame.node().size();
  }

  /**
   * Increment the array element pointer for the current frame.
   */
  public void increment() {
    currentFrame.currentIndex++;
  }

  /**
   * Push the node referenced by names onto the stack.
   */
  public void push(Object[] names) {
    push(resolve(names));
  }

  /**
   * SECTION/REPEATED scope does not look up the stack.  It only resolves
   * names against the current frame's node downward.
   */
  public void pushSection(Object[] names) {
    JsonNode node;
    if (names == null) {
      node = currentFrame.node();
    } else {
      node = resolve(names[0], currentFrame);
      for (int i = 1, len = names.length; i < len; i++) {
        if (node.isMissingNode()) {
          break;
        }
        node = nodePath(node, names[i]);
      }
    }
    push(node);
  }

  /**
   * Pushes the next element from the current array node onto the stack.
   */
  public void pushNext() {
    JsonNode node = currentFrame.node().path(currentFrame.currentIndex);
    if (node.isNull()) {
      node = undefined;
    }
    push(node);
  }

  public void setVar(String name, JsonNode node) {
    currentFrame.setVar(name, node);
  }

  public void setMacro(String name, Instruction inst) {
    currentFrame.setMacro(name, inst);
  }

  public JsonNode resolve(Object name) {
    return lookupStack(name);
  }

  public Instruction resolveMacro(String name) {
    Frame frame = currentFrame;
    while (frame != null) {
      Instruction inst = frame.getMacro(name);
      if (inst != null) {
        return inst;
      }
      frame = frame.parent();
    }
    return null;
  }

  /**
   * Lookup the JSON node referenced by the list of names.
   */
  public JsonNode resolve(Object[] names) {
    return resolve(names, currentFrame);
  }

  /**
   * Lookup the JSON node referenced by the list of names, starting at
   * the given frame. This allows formatters to selectively skip their
   * stack frame when resolving a variable reference.
   */
  public JsonNode resolve(Object[] names, Frame startingFrame) {
    if (names == null) {
      return startingFrame.node();
    }

    // Find the starting point.
    JsonNode node = lookupStack(names[0]);
    for (int i = 1, len = names.length; i < len; i++) {
      if (node.isMissingNode()) {
        return undefined;
      }
      if (node.isNull()) {
        // NOTE: Future warnings should be emitted as a side-effect not inline.
        return Constants.MISSING_NODE;
      }
      node = nodePath(node, names[i]);
    }
    return node;
  }

  private void log(Exception exc) {
    if (loggingHook != null) {
      loggingHook.log(exc);
    }
  }

  public Frame frame() {
    return currentFrame;
  }

  public void push(JsonNode node) {
    currentFrame = new Frame(currentFrame, node);
  }

  public void pop() {
    currentFrame = currentFrame.parent();
  }

  /**
   * Starting at the current frame, walk up the stack looking for the first
   * object node which contains 'name' and return that. If none match, return
   * undefined.
   */
  private JsonNode lookupStack(Object name) {
    JsonNode node = resolve(name, currentFrame);
    if (!node.isMissingNode()) {
      return node;
    }

    Frame frame = currentFrame;
    while (frame != null) {
      node = resolve(name, frame);
      if (!node.isMissingNode()) {
        return node;
      }
      if (frame.stopResolution) {
        break;
      }
      frame = frame.parent();
    }
    return undefined;
  }

  /**
   * Obtain the value for 'name' from the given stack frame's node.
   * Special variables:
   *
   *   @         current node
   *   @index0   0-based index of the current iteration context
   *   @index    1-based index of the current iteration context
   */
  private JsonNode resolve(Object name, Frame frame) {
    if (name instanceof String) {
      String strName = (String)name;

      if (strName.equals("@")) {
        return frame.node();
      } else if (strName.startsWith("@")) {
        boolean isIndex = name.equals("@index");
        if (isIndex || name.equals("@index0")) {
          if (frame.currentIndex != -1) {
            // @zindex is 0-based, @index is 1-based
            int index = isIndex ? frame.currentIndex + 1 : frame.currentIndex;
            return new IntNode(index);
          }
          return Constants.MISSING_NODE;
        }
        JsonNode node = frame.getVar(strName);
        return (node == null) ? Constants.MISSING_NODE : node;
      }

      // Fall through
    }
    return nodePath(frame.node(), name);
  }

  private JsonNode nodePath(JsonNode node, Object key) {
    if (key instanceof Integer) {
      return node.path((int) key);
    }
    return node.path((String) key);
  }

  public void addError(ErrorInfo error) {
    if (errors == null) {
      errors = new ArrayList<>();
    }
    errors.add(error);
  }

}
