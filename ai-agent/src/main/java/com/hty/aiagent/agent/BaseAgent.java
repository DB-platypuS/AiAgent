package com.hty.aiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.hty.aiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程
 *
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能
 * 子类必须实现step方法
 */
@Data
@Slf4j
public abstract class BaseAgent {

    //核心属性
    private String name;

    //提示
    private String systemPrompt;
    private String nextStepPrompt;

    //状态
    private AgentState state = AgentState.IDLE;//默认为空闲

    //执行控制
    private int maxSteps = 10;
    private int currentStep = 0;

    //LLM
    private ChatClient chatClient;

    //Memory(需要自主维护会话上下文)
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     * @param userPrompt
     * @return
     */
    public String run(String userPrompt){
        //1.基础校验
        if (this.state != AgentState.IDLE){
            throw new RuntimeException("Cannot run agent from state: "+ this.state);
        }
        if (StrUtil.isBlank(userPrompt)){
            throw new RuntimeException("Cannot run agent with empty prompt");
        }
        try {
            //执行更改状态
            this.state = AgentState.RUNNING;
            //记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            //保存结果列表
            List<String> results = new ArrayList<>();
            //2.执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++){
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", + stepNumber, maxSteps);
                //单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            //检查是否超出步骤限制
            if (currentStep > maxSteps){
                state = AgentState.FINISHED;
                results.add("Terminated Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            log.error("Error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            //3.清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     * @param userPrompt
     * @return
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 3分钟超时

        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            //1.基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }
            try {
                //执行更改状态
                this.state = AgentState.RUNNING;
                //记录消息上下文
                messageList.add(new UserMessage(userPrompt));
                //保存结果列表
                List<String> results = new ArrayList<>();
                //2.执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", +stepNumber, maxSteps);
                    //单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 输出当前每一步结果到 SSE
                    sseEmitter.send(result);
                }
                //检查是否超出步骤限制
                if (currentStep > maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                // 正常完成
                sseEmitter.complete();
            } catch (Exception e) {
                log.error("Error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                //3.清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });

        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING){
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return sseEmitter;
    }

    /**
     * 定义单个步骤
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup(){
        //交给子类实现
    }

}
