/*
 * Copyright 2019 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jmix.data.impl.querymacro;

import com.google.common.base.Strings;
import io.jmix.core.DateTimeTransformations;
import io.jmix.core.TimeSource;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("data_DateAfterQueryMacroHandler")
@Scope("prototype")
public class DateAfterMacroHandler extends AbstractQueryMacroHandler {

    protected static final Pattern MACRO_PATTERN = Pattern.compile("@dateAfter\\s*\\(([^)]+)\\)");
    protected static final Pattern NOW_PARAM_PATTERN = Pattern.compile("(now)\\s*([\\d\\s+-]*)");

    @Autowired
    protected DateTimeTransformations transformations;
    @Autowired
    protected TimeSource timeSource;

    protected Map<String, Object> namedParameters;
    protected List<MacroArgs> paramArgs = new ArrayList<>();

    public DateAfterMacroHandler() {
        super(MACRO_PATTERN);
    }

    @Override
    protected String doExpand(String macro) {
        count++;
        String[] args = macro.split(",");
        if (args.length != 2 && args.length != 3)
            throw new RuntimeException("Invalid macro: " + macro);

        String field = args[0].trim();
        String param = args[1].trim();
        TimeZone timeZone = getTimeZoneFromArgs(args, 2);
        String paramName;

        Matcher matcher = NOW_PARAM_PATTERN.matcher(param);
        if (matcher.find()) {
            int offset = 0;
            try {
                String expr = matcher.group(2);
                if (!Strings.isNullOrEmpty(expr)) {
                    expr = expr.replaceAll("\\s+","");
                    offset = Integer.parseInt(expr);
                }
            } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid macro argument: " + param, e);
            }
            paramName = args[0].trim().replace(".", "_") + "_" + count + "_" + 1;
            paramArgs.add(new MacroArgs(paramName, timeZone, offset, true));
        } else {
            paramName = param.substring(1);
            paramArgs.add(new MacroArgs(paramName, timeZone));
        }

        return String.format("(%s >= :%s)", field, paramName);
    }

    @Override
    public void setQueryParams(Map<String, Object> namedParameters) {
        this.namedParameters = namedParameters;
    }

    @Override
    public Map<String, Object> getParams() {
        Map<String, Object> params = new HashMap<>();
        for (MacroArgs paramArg : paramArgs) {
            Class javaType;
            ZonedDateTime zonedDateTime;
            TimeZone timeZone = paramArg.getTimeZone();
            String paramName = paramArg.getParamName();;
            if (timeZone == null)
                timeZone = TimeZone.getDefault();
            if (paramArg.isNow()) {
                zonedDateTime = timeSource.now();
                javaType = expandedParamTypes.get(paramName);
                if (javaType == null)
                    throw new RuntimeException(String.format("Type of parameter %s not resolved", paramName));
            } else {
                Object date = namedParameters.get(paramName);
                if (date == null)
                    throw new RuntimeException(String.format("Parameter %s not found for macro", paramName));
                javaType = date.getClass();
                zonedDateTime = transformations.transformToZDT(date);
            }
            if (transformations.isDateTypeSupportsTimeZones(javaType)) {
                zonedDateTime = zonedDateTime.withZoneSameInstant(timeZone.toZoneId());
            }
            zonedDateTime = zonedDateTime.plusDays(paramArg.getOffset()).truncatedTo(ChronoUnit.DAYS);
            Object paramValue = transformations.transformFromZDT(zonedDateTime, javaType);
            params.put(paramName, paramValue);
        }
        return params;
    }

    @Override
    public String replaceQueryParams(String queryString, Map<String, Object> params) {
        return queryString;
    }
}