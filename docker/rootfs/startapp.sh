#!/bin/sh

mkdir -p /config/log
exec /opt/portfolio/PortfolioPerformance \
    > /config/log/pp_out.log \
    2> /config/log/pp_err.log

