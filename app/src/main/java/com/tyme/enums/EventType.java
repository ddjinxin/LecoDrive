package com.tyme.enums;

/**
 * 事件类型
 *
 * @author 6tail
 */
public enum EventType {
  SOLAR_DAY(0, "公历日期"),
  SOLAR_WEEK(1, "几月第几个星期几"),
  LUNAR_DAY(2, "农历日期"),
  TERM_DAY(3, "节气日期"),
  TERM_HS(4, "节气天干"),
  TERM_EB(5, "节气地支");

  /**
   * 代码
   */
  private final int code;

  /**
   * 名称
   */
  private final String name;

  EventType(int code, String name) {
    this.code = code;
    this.name = name;
  }

  public static EventType fromCode(Integer code) {
    if (null == code) {
      return null;
    }
    for (EventType item : values()) {
      if (item.getCode() == code) {
        return item;
      }
    }
    return null;
  }

  public static EventType fromName(String name) {
    if (null == name) {
      return null;
    }
    for (EventType item : values()) {
      if (item.getName().equals(name)) {
        return item;
      }
    }
    return null;
  }

  public int getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName();
  }

}
