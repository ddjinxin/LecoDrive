package com.tyme.eightchar.provider.impl;

import com.tyme.eightchar.ChildLimitInfo;
import com.tyme.solar.SolarTerm;
import com.tyme.solar.SolarTime;

/**
 * 元亨利贞的童限计算
 *
 * @author 6tail
 */
public class China95ChildLimitProvider extends AbstractChildLimitProvider {
  @Override
  public ChildLimitInfo getInfo(SolarTime birthTime, SolarTerm term) {
    // 出生时刻和节令时刻相差的分钟数
    int minutes = Math.abs(term.getJulianDay().getSolarTime().subtract(birthTime)) / 60;
    int year = minutes / 4320;
    minutes %= 4320;
    int month = minutes / 360;
    minutes %= 360;
    int day = minutes / 12;

    return next(birthTime, year, month, day, 0, 0, 0);
  }

}
