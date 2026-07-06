package com.tianji.tools;


import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;

import com.tianji.api.client.trade.TradeClient;
import com.tianji.api.dto.trade.OrderConfirmVO;
import com.tianji.common.utils.UserContext;
import com.tianji.config.ToolResultHolder;
import com.tianji.constants.Constant;
import com.tianji.tools.result.PrePlaceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * @Name: OrderTools
 * @Author: Natural Pride
 * @CreateTime: 2026/7/6 10:41
 * @Description:
 */

@Component
@RequiredArgsConstructor
public class OrderTools {

    private final TradeClient tradeClient;

    /**
     * 预下单
     * @param ids 课程ids
     * @param toolContext 工具上下文
     * @return 预下单结果
     */

    @Tool(description = Constant.Tools.PRE_PLACE_ORDER)
    public PrePlaceOrder prePlaceOrder(@ToolParam(description = Constant.ToolParams.COURSE_IDS) List<Number> ids,
                                       ToolContext toolContext) {
        UserContext.setUser(Convert.toLong(toolContext.getContext().get(Constant.USER_ID)));
        // 大模型传入的ids，可能是int类型，所以转化为long类型，再调用Feign
        OrderConfirmVO orderConfirmVO = tradeClient.prePlaceOrder(CollStreamUtil.toList(ids, Number::longValue));

        return Optional.ofNullable(orderConfirmVO)
                .map(PrePlaceOrder::of)
                .map(prePlaceOrder -> {
                    String field = StrUtil.lowerFirst(prePlaceOrder.getClass().getSimpleName());
                    String requestId = Convert.toStr(toolContext.getContext().get(Constant.REQUEST_ID));
                    ToolResultHolder.put(requestId, field, prePlaceOrder);
                    return prePlaceOrder;
                })
                .orElse(null);
    }
}
