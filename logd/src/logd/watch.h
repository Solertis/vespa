// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vector>

namespace logdemon {

class Forwarder;
class ConfSub;

class Watcher
{
private:
    std::vector<char>  _buffer;
    ConfSub          & _confsubscriber;
    Forwarder        & _forwarder;
    int                _wfd;
    char * getBuf() { return &_buffer[0]; }
    long getBufSize() const { return _buffer.size(); }
public:
    Watcher(const Watcher& other) = delete;
    Watcher& operator=(const Watcher& other) = delete;
    Watcher(ConfSub &cfs, Forwarder &fw);
    ~Watcher();

    void watchfile();
    void removeOldLogs(const char *prefix);
};

}
